use std::cell::{Cell, RefCell};
use std::ffi::CString;

use tcdns::{process_response, record_answers, DnsPolicy, Outcome};

const TYPE_A: u16 = 1;
const TYPE_AAAA: u16 = 28;
const TYPE_HTTPS: u16 = 65;
const CLASS_IN: u16 = 1;

#[derive(Default)]
struct TestPolicy {
    records: RefCell<Vec<(String, String, String, i32)>>,
    blocked: bool,
    blocked_rcode: u8,
    policy_calls: Cell<usize>,
}

impl DnsPolicy for TestPolicy {
    fn record_answer(&self, qname: &str, aname: &str, resource: &str, ttl: i32) {
        self.records.borrow_mut().push((
            qname.to_owned(),
            aname.to_owned(),
            resource.to_owned(),
            ttl,
        ));
    }

    fn is_domain_blocked(&self, _qname: &str) -> bool {
        self.policy_calls.set(self.policy_calls.get() + 1);
        self.blocked
    }

    fn blocked_rcode(&self) -> u8 {
        self.blocked_rcode
    }
}

struct NoopPolicy;

impl DnsPolicy for NoopPolicy {
    fn record_answer(&self, _qname: &str, _aname: &str, _resource: &str, _ttl: i32) {}
}

fn name(name: &str) -> Vec<u8> {
    let mut encoded = Vec::new();
    for label in name.split('.') {
        encoded.push(label.len() as u8);
        encoded.extend_from_slice(label.as_bytes());
    }
    encoded.push(0);
    encoded
}

fn question(encoded_name: &[u8], qtype: u16) -> Vec<u8> {
    let mut result = encoded_name.to_vec();
    result.extend_from_slice(&qtype.to_be_bytes());
    result.extend_from_slice(&CLASS_IN.to_be_bytes());
    result
}

fn answer(encoded_name: &[u8], qtype: u16, ttl: u32, rdata: &[u8]) -> Vec<u8> {
    let mut result = encoded_name.to_vec();
    result.extend_from_slice(&qtype.to_be_bytes());
    result.extend_from_slice(&CLASS_IN.to_be_bytes());
    result.extend_from_slice(&ttl.to_be_bytes());
    result.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
    result.extend_from_slice(rdata);
    result
}

fn response(questions: &[Vec<u8>], answers: &[Vec<u8>]) -> Vec<u8> {
    let mut result = vec![0u8; 12];
    result[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
    result[4..6].copy_from_slice(&(questions.len() as u16).to_be_bytes());
    result[6..8].copy_from_slice(&(answers.len() as u16).to_be_bytes());
    for question in questions {
        result.extend_from_slice(question);
    }
    for answer in answers {
        result.extend_from_slice(answer);
    }
    result
}

fn a_answer(encoded_name: &[u8]) -> Vec<u8> {
    answer(encoded_name, TYPE_A, 300, &[203, 0, 113, 7])
}

#[test]
fn records_a_and_aaaa_answers() {
    let qname = name("tracker.example");
    let message = response(
        &[question(&qname, TYPE_A)],
        &[
            answer(&[0xc0, 12], TYPE_A, 300, &[203, 0, 113, 7]),
            answer(
                &[0xc0, 12],
                TYPE_AAAA,
                60,
                &[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
            ),
        ],
    );
    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 2);
    assert_eq!(records[0].2, "203.0.113.7");
    assert_eq!(records[1].2, "2001:db8::1");
    assert_eq!(records[1].3, 60);
}

#[test]
fn query_messages_are_ignored() {
    let qname = name("tracker.example");
    let mut message = response(&[question(&qname, TYPE_A)], &[a_answer(&[0xc0, 12])]);
    message[2..4].copy_from_slice(&0x0100u16.to_be_bytes());
    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    assert!(policy.records.borrow().is_empty());
}

#[test]
fn labels_then_pointer_resumes_after_pointer_and_records_answer() {
    let qname = name("target.example");
    let mut answer_name = vec![3, b'w', b'w', b'w', 0xc0, 12];
    let mut message = response(&[question(&qname, TYPE_A)], &[]);
    answer_name.extend_from_slice(&TYPE_A.to_be_bytes());
    answer_name.extend_from_slice(&CLASS_IN.to_be_bytes());
    answer_name.extend_from_slice(&300u32.to_be_bytes());
    answer_name.extend_from_slice(&4u16.to_be_bytes());
    answer_name.extend_from_slice(&[203, 0, 113, 7]);
    message[6..8].copy_from_slice(&1u16.to_be_bytes());
    message.extend_from_slice(&answer_name);

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    assert_eq!(policy.records.borrow().len(), 1);
    assert_eq!(policy.records.borrow()[0].1, "www.target.example");
}

#[test]
fn reserved_name_length_bits_are_rejected() {
    for reserved in [0x40u8, 0x80] {
        let message = response(&[question(&[reserved, 0], TYPE_A)], &[a_answer(&[0])]);
        let policy = TestPolicy::default();
        assert_eq!(
            process_response(&mut message.clone(), &policy),
            Outcome::Unchanged
        );
        assert!(policy.records.borrow().is_empty());
    }
}

fn answer_with_name(message: &mut Vec<u8>, encoded_name: &[u8]) {
    let answer = a_answer(encoded_name);
    message[6..8].copy_from_slice(&1u16.to_be_bytes());
    message.extend_from_slice(&answer);
}

#[test]
fn name_depth_label_and_wire_octet_caps_reject_malformed_names() {
    let qname = name("valid.example");

    let mut deep = response(&[question(&qname, TYPE_A)], &[]);
    let chain_offset = 12 + question(&qname, TYPE_A).len() + 16;
    let mut pointer = vec![0xc0, chain_offset as u8];
    let chain_start = chain_offset;
    for index in 0..9usize {
        let target = chain_start + (index + 1) * 2;
        pointer.extend_from_slice(&[0xc0, target as u8]);
    }
    pointer.push(0);
    answer_with_name(&mut deep, &pointer[..2]);
    deep.extend_from_slice(&pointer[2..]);
    let policy = TestPolicy::default();
    assert_eq!(process_response(&mut deep, &policy), Outcome::Unchanged);

    let labels = vec![1u8; 130];
    let mut too_many_labels = vec![0u8; 12];
    too_many_labels[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
    too_many_labels[4..6].copy_from_slice(&1u16.to_be_bytes());
    too_many_labels[6..8].copy_from_slice(&1u16.to_be_bytes());
    too_many_labels.extend_from_slice(&labels);
    too_many_labels.push(0);
    too_many_labels.extend_from_slice(&TYPE_A.to_be_bytes());
    too_many_labels.extend_from_slice(&CLASS_IN.to_be_bytes());
    too_many_labels.extend_from_slice(&a_answer(&[0]));
    assert_eq!(
        process_response(&mut too_many_labels, &NoopPolicy),
        Outcome::Unchanged
    );

    let mut too_long = vec![0u8; 12];
    too_long[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
    too_long[4..6].copy_from_slice(&1u16.to_be_bytes());
    too_long[6..8].copy_from_slice(&1u16.to_be_bytes());
    for _ in 0..4 {
        too_long.push(63);
        too_long.extend(std::iter::repeat_n(b'x', 63));
    }
    too_long.push(0);
    too_long.extend_from_slice(&TYPE_A.to_be_bytes());
    too_long.extend_from_slice(&CLASS_IN.to_be_bytes());
    too_long.extend_from_slice(&a_answer(&[0]));
    assert_eq!(
        process_response(&mut too_long, &NoopPolicy),
        Outcome::Unchanged
    );

    let mut looped = response(&[question(&qname, TYPE_A)], &[]);
    let offset = 12 + question(&qname, TYPE_A).len();
    answer_with_name(&mut looped, &[0xc0, offset as u8]);
    assert_eq!(
        process_response(&mut looped, &NoopPolicy),
        Outcome::Unchanged
    );
}

#[test]
fn invalid_utf8_and_nul_are_replaced_before_callbacks() {
    let raw = [3, 0xff, 0, b'a', 0];
    let mut message = response(&[question(&raw, TYPE_A)], &[]);
    let q_end = 12 + raw.len() + 4;
    let pointer = [0xc0, 12];
    message[6..8].copy_from_slice(&1u16.to_be_bytes());
    message.extend_from_slice(&a_answer(&pointer));
    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].0, "��a");
    assert_eq!(records[0].1, "��a");
    assert!(CString::new(records[0].0.as_bytes()).is_ok());
    assert_eq!(q_end, 12 + raw.len() + 4);
}

#[test]
fn supplementary_unicode_is_replaced_for_modified_utf8_callbacks() {
    let raw = [4, 0xf0, 0x9f, 0x98, 0x80, 0];
    let mut message = response(&[question(&raw, TYPE_A)], &[]);
    message[6..8].copy_from_slice(&1u16.to_be_bytes());
    message.extend_from_slice(&a_answer(&[0xc0, 12]));
    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].0, "�");
    assert_eq!(records[0].1, "�");
}

#[test]
fn root_question_never_calls_policy_and_is_unchanged() {
    let root = [0u8];
    let mut message = response(
        &[question(&root, TYPE_A)],
        &[answer(&[0], TYPE_HTTPS, 300, &[])],
    );
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    assert_eq!(process_response(&mut message, &policy), Outcome::Unchanged);
    assert_eq!(policy.policy_calls.get(), 0);
}

#[test]
fn ttl_is_saturated_and_malformed_address_lengths_are_ignored() {
    let qname = name("tracker.example");
    let message = response(
        &[question(&qname, TYPE_A)],
        &[
            answer(&[0xc0, 12], TYPE_A, u32::MAX, &[203, 0, 113, 7]),
            answer(&[0xc0, 12], TYPE_A, 300, &[203, 0, 113, 7, 99]),
        ],
    );
    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    assert_eq!(policy.records.borrow().len(), 1);
    assert_eq!(policy.records.borrow()[0].3, i32::MAX);

    let malformed_length = response(
        &[question(&qname, TYPE_A)],
        &[answer(&[0xc0, 12], TYPE_A, 300, &[203, 0, 113, 7, 99])],
    );
    let policy = TestPolicy::default();
    record_answers(&malformed_length, &policy);
    assert!(policy.records.borrow().is_empty());
}

#[test]
fn multiple_questions_are_consumed_before_answers() {
    let first = name("first.example");
    let second = name("second.example");
    let mut message = response(
        &[question(&first, TYPE_A), question(&second, TYPE_A)],
        &[answer(&[0xc0, 12], TYPE_A, 300, &[203, 0, 113, 7])],
    );
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    let outcome = process_response(&mut message, &policy);
    assert_eq!(policy.records.borrow().len(), 1);
    assert_eq!(policy.records.borrow()[0].0, "first.example");
    assert!(
        matches!(outcome, Outcome::Blanked { new_len, qtype: TYPE_A, .. } if new_len == 12 + first.len() + 4 + second.len() + 4)
    );
    assert_eq!(&message[6..12], &[0, 0, 0, 0, 0, 0]);
}

#[test]
fn blocked_rcode_is_masked_to_four_bits() {
    let qname = name("blocked.example");
    let mut message = response(&[question(&qname, TYPE_A)], &[a_answer(&[0xc0, 12])]);
    let policy = TestPolicy {
        blocked: true,
        blocked_rcode: 0x1f,
        ..TestPolicy::default()
    };
    assert!(matches!(
        process_response(&mut message, &policy),
        Outcome::Blanked { rcode: 0x0f, .. }
    ));
    assert_eq!(message[3] & 0x0f, 0x0f);
}

#[test]
fn malformed_answer_after_valid_a_records_a_but_does_not_blank() {
    let qname = name("tracker.example");
    let mut malformed = answer(&[0xc0, 12], TYPE_HTTPS, 300, &[]);
    let rdlen_offset = malformed.len() - 2;
    malformed[rdlen_offset..].copy_from_slice(&100u16.to_be_bytes());
    let mut message = response(
        &[question(&qname, TYPE_A)],
        &[a_answer(&[0xc0, 12]), malformed],
    );
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    assert_eq!(process_response(&mut message, &policy), Outcome::Unchanged);
    assert_eq!(policy.records.borrow().len(), 1);
}

#[test]
fn process_response_fuzz_smoke_returns_for_fifty_thousand_inputs() {
    let mut state = 0x9e37_79b9u32;
    for index in 0..50_000usize {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        let length = (state as usize ^ index) % 601;
        let mut message = vec![0u8; length];
        for byte in &mut message {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            *byte = state as u8;
        }
        if index % 2 == 0 && message.len() >= 12 {
            message[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
            message[4..6].copy_from_slice(&1u16.to_be_bytes());
        }
        let _ = process_response(&mut message, &NoopPolicy);
    }
}

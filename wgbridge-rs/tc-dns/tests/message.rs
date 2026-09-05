use std::cell::{Cell, RefCell};
use std::ffi::CString;

use tcdns::{process_partial_response, process_response, record_answers, DnsPolicy, Outcome};

const TYPE_A: u16 = 1;
const TYPE_AAAA: u16 = 28;
const TYPE_CNAME: u16 = 5;
const TYPE_HTTPS: u16 = 65;
const TYPE_RRSIG: u16 = 46;
const TYPE_OPT: u16 = 41;
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

fn cname_answer(owner: &str, target: &str, ttl: u32) -> Vec<u8> {
    let owner = name(owner);
    let target = name(target);
    answer(&owner, TYPE_CNAME, ttl, &target)
}

fn named_a_answer(owner: &str, ttl: u32, address: [u8; 4]) -> Vec<u8> {
    let owner = name(owner);
    answer(&owner, TYPE_A, ttl, &address)
}

fn named_aaaa_answer(owner: &str, ttl: u32, address: [u8; 16]) -> Vec<u8> {
    let owner = name(owner);
    answer(&owner, TYPE_AAAA, ttl, &address)
}

/// Encodes a compression pointer to `offset` in the message.
fn ptr(offset: usize) -> [u8; 2] {
    [0xc0 | ((offset >> 8) as u8), (offset & 0xff) as u8]
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
fn pointer_preserves_literal_dot_inside_wire_label() {
    let qname = [3, b'a', b'.', b'b', 0];
    let answer_name = [1, b'x', 0xc0, 12];
    let message = response(&[question(&qname, TYPE_A)], &[a_answer(&answer_name)]);

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].1, "x.a.b");
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
fn partial_blocked_response_blanks_visible_tail_without_shrinking() {
    let qname_encoded = name("blocked.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let question_end = 12 + question_bytes.len();
    let mut message = response(
        std::slice::from_ref(&question_bytes),
        &[a_answer(&[0xc0, 12])],
    );
    message.truncate(question_end + 5);
    let original_len = message.len();

    let policy = TestPolicy {
        blocked: true,
        blocked_rcode: 0x1f,
        ..TestPolicy::default()
    };
    let outcome = process_partial_response(&mut message, &policy);

    assert!(matches!(
        outcome,
        Outcome::Blanked {
            new_len,
            qname,
            qtype: TYPE_A,
            rcode: 0x0f,
        } if new_len == question_end && qname == "blocked.example"
    ));
    assert_eq!(message.len(), original_len);
    assert!(message[question_end..].iter().all(|byte| *byte == 0));
    assert_eq!(&message[6..12], &[0, 0, 0, 0, 0, 0]);
}

#[test]
fn partial_unblocked_response_is_untouched() {
    let qname_encoded = name("allowed.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let question_end = 12 + question_bytes.len();
    let mut message = response(
        std::slice::from_ref(&question_bytes),
        &[a_answer(&[0xc0, 12])],
    );
    message.truncate(question_end + 5);
    let original = message.clone();
    let policy = TestPolicy::default();

    assert_eq!(
        process_partial_response(&mut message, &policy),
        Outcome::Unchanged
    );
    assert_eq!(message, original);
    assert!(policy.records.borrow().is_empty());
}

#[test]
fn partial_response_records_answers_before_truncation() {
    let qname_encoded = name("tracker.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let question_end = 12 + question_bytes.len();
    let first = a_answer(&[0xc0, 12]);
    let second = a_answer(&[0xc0, 12]);
    let mut message = response(
        std::slice::from_ref(&question_bytes),
        &[first.clone(), second],
    );
    message.truncate(question_end + first.len() + 5);

    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    let outcome = process_partial_response(&mut message, &policy);

    assert!(matches!(
        outcome,
        Outcome::Blanked { new_len, .. } if new_len == question_end
    ));
    let records = policy.records.borrow();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].0, "tracker.example");
    assert_eq!(records[0].2, "203.0.113.7");
    drop(records);
    assert!(message[question_end..].iter().all(|byte| *byte == 0));
}

#[test]
fn partial_response_with_truncated_question_is_unchanged() {
    let qname_encoded = name("truncated.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let full = response(
        std::slice::from_ref(&question_bytes),
        &[a_answer(&[0xc0, 12])],
    );
    let mut message = full[..15].to_vec();
    let original = message.clone();
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };

    assert_eq!(
        process_partial_response(&mut message, &policy),
        Outcome::Unchanged
    );
    assert_eq!(message, original);
    assert_eq!(policy.policy_calls.get(), 0);
}

#[test]
fn partial_response_with_empty_qname_is_unchanged() {
    let mut message = response(&[question(&[0], TYPE_A)], &[a_answer(&[0xc0, 12])]);
    let original = message.clone();
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };

    assert_eq!(
        process_partial_response(&mut message, &policy),
        Outcome::Unchanged
    );
    assert_eq!(message, original);
    assert_eq!(policy.policy_calls.get(), 0);
}

/// A pointer's own 2 bytes must not be charged against the 255-octet
/// uncompressed-name cap: that cap is RFC 1035's limit on the *expanded*
/// name, and a legal near-maximum name reached through a compression
/// pointer used to be wrongly rejected because the pointer bytes and the
/// fully expanded target shared one budget.
#[test]
fn near_maximum_length_name_reached_via_pointer_is_accepted_and_can_be_blocked() {
    // Wire-encoded QNAME of exactly 254 octets: labels 63/63/63/60 plus the
    // root byte, i.e. a 249-character domain (comfortably inside the
    // 253-character legal limit).
    let label_lens = [63usize, 63, 63, 60];
    let mut qname_encoded = Vec::new();
    let mut labels = Vec::new();
    for len in label_lens {
        qname_encoded.push(len as u8);
        let label = vec![b'a'; len];
        qname_encoded.extend_from_slice(&label);
        labels.push(String::from_utf8(label).expect("ascii label"));
    }
    qname_encoded.push(0);
    assert_eq!(qname_encoded.len(), 254);
    let expected_qname = labels.join(".");

    let mut message = response(
        &[question(&qname_encoded, TYPE_A)],
        &[a_answer(&[0xc0, 12])],
    );
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };

    assert!(matches!(
        process_response(&mut message, &policy),
        Outcome::Blanked { .. }
    ));
    let records = policy.records.borrow();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].0, expected_qname);
    assert_eq!(records[0].1, expected_qname);
    assert_eq!(records[0].2, "203.0.113.7");
}

/// A genuinely over-long uncompressed name (more than 255 octets of labels)
/// must still be rejected — proving the cap on expanded-name label octets
/// stays active after the pointer-charge fix. The question name stays
/// short and valid; it is the answer's own (uncompressed) owner name that
/// exceeds the limit, exercising the same `read_dns_name_inner` label
/// accounting the fix touched.
#[test]
fn over_long_uncompressed_answer_owner_name_is_still_rejected() {
    let qname = name("q.example");

    // Four 63-octet labels alone already total 256 octets before the root
    // byte, exceeding MAX_NAME_OCTETS regardless of compression.
    let mut owner_name = Vec::new();
    for _ in 0..4 {
        owner_name.push(63u8);
        owner_name.extend(std::iter::repeat_n(b'a', 63));
    }
    owner_name.push(0);
    assert!(owner_name.len() > 255);

    let mut message = response(&[question(&qname, TYPE_A)], &[a_answer(&owner_name)]);
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };

    assert_eq!(process_response(&mut message, &policy), Outcome::Unchanged);
    assert!(policy.records.borrow().is_empty());
}

/// A CDN-fronted tracker's typical response shape: qname CNAME intermediate,
/// intermediate CNAME target, target A <ip>. Each owner name after the
/// question is a compression pointer into the previous record's RDATA, the
/// way real resolvers encode chains. Every validated owner→target link is
/// recorded against the terminal address. The first row therefore carries
/// the *original question* qname, while each intermediate alias survives as
/// its own qname group without a synthetic terminal self-row.
#[test]
fn cname_chain_records_each_a_alias_with_question_qname() {
    let qname_encoded = name("chain.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let question_end = 12 + question_bytes.len();

    // cname1's owner name is a 2-byte pointer to the question's qname.
    let mid_encoded = name("mid.chain.example");
    let cname1 = answer(&ptr(12), TYPE_CNAME, 300, &mid_encoded);
    // Every record here has a 2-byte pointer owner name, so the fixed
    // header (name + type + class + ttl + rdlen) is always 12 bytes before
    // RDATA starts.
    let mid_rdata_offset = question_end + 12;
    let cname1_end = question_end + cname1.len();

    // cname2's owner name points at "mid.chain.example" inside cname1's own
    // RDATA, exactly as a resolver compresses a chain it is building live.
    let cdn_encoded = name("cdn.example");
    let cname2 = answer(&ptr(mid_rdata_offset), TYPE_CNAME, 300, &cdn_encoded);
    let cdn_rdata_offset = cname1_end + 12;

    // The A record's owner name points at "cdn.example" inside cname2's
    // RDATA.
    let a_record = answer(&ptr(cdn_rdata_offset), TYPE_A, 300, &[203, 0, 113, 7]);

    let message = response(
        std::slice::from_ref(&question_bytes),
        &[cname1, cname2, a_record],
    );

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 2);
    assert_eq!(
        records.as_slice(),
        [
            (
                "chain.example".to_owned(),
                "mid.chain.example".to_owned(),
                "203.0.113.7".to_owned(),
                300,
            ),
            (
                "mid.chain.example".to_owned(),
                "cdn.example".to_owned(),
                "203.0.113.7".to_owned(),
                300,
            ),
        ]
    );
    drop(records);

    let mut blanked = message;
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    let outcome = process_response(&mut blanked, &policy);
    assert!(matches!(
        outcome,
        Outcome::Blanked { new_len, qtype: TYPE_A, .. } if new_len == question_end
    ));
    assert_eq!(&blanked[6..12], &[0, 0, 0, 0, 0, 0]);
}

/// Same CNAME-chain shape as above, but the chain terminates in an AAAA
/// record instead of A.
#[test]
fn cname_chain_ending_in_aaaa_records_each_alias_with_question_qname() {
    let qname_encoded = name("chain6.example");
    let question_bytes = question(&qname_encoded, TYPE_AAAA);
    let question_end = 12 + question_bytes.len();

    let mid_encoded = name("mid.chain6.example");
    let cname1 = answer(&ptr(12), TYPE_CNAME, 300, &mid_encoded);
    let mid_rdata_offset = question_end + 12;
    let cname1_end = question_end + cname1.len();

    let cdn_encoded = name("cdn6.example");
    let cname2 = answer(&ptr(mid_rdata_offset), TYPE_CNAME, 300, &cdn_encoded);
    let cdn_rdata_offset = cname1_end + 12;

    let ip = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2];
    let aaaa_record = answer(&ptr(cdn_rdata_offset), TYPE_AAAA, 300, &ip);

    let message = response(
        std::slice::from_ref(&question_bytes),
        &[cname1, cname2, aaaa_record],
    );

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 2);
    assert_eq!(
        records.as_slice(),
        [
            (
                "chain6.example".to_owned(),
                "mid.chain6.example".to_owned(),
                "2001:db8::2".to_owned(),
                300,
            ),
            (
                "mid.chain6.example".to_owned(),
                "cdn6.example".to_owned(),
                "2001:db8::2".to_owned(),
                300,
            ),
        ]
    );
    drop(records);

    let mut blanked = message;
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    let outcome = process_response(&mut blanked, &policy);
    assert!(matches!(
        outcome,
        Outcome::Blanked { new_len, qtype: TYPE_AAAA, .. } if new_len == question_end
    ));
    assert_eq!(&blanked[6..12], &[0, 0, 0, 0, 0, 0]);
}

/// CNAME links are a graph, rather than an ordered list. The intermediate
/// name gets its own qname group, so an Android lookup grouped by qname keeps
/// the complete chain and can still find a tracker at that name.
#[test]
fn cname_chain_is_order_independent_and_canonicalises_names() {
    let qname = name("SAFE.EXAMPLE");
    let question_bytes = question(&qname, TYPE_A);
    let message = response(
        std::slice::from_ref(&question_bytes),
        &[
            named_a_answer("FINAL.EXAMPLE", 70, [203, 0, 113, 8]),
            cname_answer("INTERMEDIATE.TRACKER.EXAMPLE", "FINAL.EXAMPLE", 40),
            cname_answer("SAFE.EXAMPLE", "INTERMEDIATE.TRACKER.EXAMPLE", 90),
        ],
    );

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(
        records.as_slice(),
        [
            (
                "safe.example".to_owned(),
                "intermediate.tracker.example".to_owned(),
                "203.0.113.8".to_owned(),
                40,
            ),
            (
                "intermediate.tracker.example".to_owned(),
                "final.example".to_owned(),
                "203.0.113.8".to_owned(),
                40,
            ),
        ]
    );
    assert!(records
        .iter()
        .any(|record| record.0 == "intermediate.tracker.example"));
    assert!(!records
        .iter()
        .any(|record| record.0 == "final.example" && record.1 == "final.example"));
}

#[test]
fn multiple_a_and_aaaa_records_are_deduplicated_and_keep_ttl_minima() {
    let qname = name("alias.example");
    let question_bytes = question(&qname, TYPE_A);
    let ipv6 = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9];
    let message = response(
        std::slice::from_ref(&question_bytes),
        &[
            cname_answer("alias.example", "terminal.example", 600),
            named_a_answer("terminal.example", 300, [203, 0, 113, 9]),
            named_a_answer("terminal.example", 100, [203, 0, 113, 9]),
            named_a_answer("terminal.example", 500, [198, 51, 100, 9]),
            named_aaaa_answer("terminal.example", 60, ipv6),
        ],
    );

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 3);
    for resource in ["203.0.113.9", "198.51.100.9", "2001:db8::9"] {
        let alias = records
            .iter()
            .find(|record| record.0 == "alias.example" && record.2 == resource)
            .expect("alias mapping present");
        assert_eq!(alias.1, "terminal.example");
        let expected_ttl = match resource {
            "203.0.113.9" => 100,
            "198.51.100.9" => 500,
            _ => 60,
        };
        assert_eq!(alias.3, expected_ttl);
    }
    assert!(!records
        .iter()
        .any(|record| record.0 == "terminal.example" && record.1 == "terminal.example"));
}

#[test]
fn unrelated_cname_and_address_are_not_attributed_to_the_question() {
    let qname = name("requested.example");
    let question_bytes = question(&qname, TYPE_A);
    let message = response(
        std::slice::from_ref(&question_bytes),
        &[
            cname_answer("unrelated.example", "unrelated.cdn.example", 300),
            named_a_answer("unrelated.cdn.example", 300, [203, 0, 113, 10]),
        ],
    );

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    assert!(policy.records.borrow().is_empty());
}

#[test]
fn cname_loop_stops_without_an_address_mapping() {
    let qname = name("loop-a.example");
    let question_bytes = question(&qname, TYPE_A);
    let message = response(
        std::slice::from_ref(&question_bytes),
        &[
            cname_answer("loop-a.example", "loop-b.example", 300),
            cname_answer("loop-b.example", "loop-a.example", 300),
            named_a_answer("loop-b.example", 300, [203, 0, 113, 13]),
        ],
    );

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    assert!(policy.records.borrow().is_empty());
}

#[test]
fn cname_chain_depth_bound_stops_before_an_unbounded_terminal() {
    let qname = name("depth-0.example");
    let question_bytes = question(&qname, TYPE_A);
    let mut answers = Vec::new();
    for index in 0..10 {
        answers.push(cname_answer(
            &format!("depth-{index}.example"),
            &format!("depth-{}.example", index + 1),
            300,
        ));
    }
    answers.push(named_a_answer("depth-10.example", 300, [203, 0, 113, 11]));
    let message = response(std::slice::from_ref(&question_bytes), &answers);

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    assert!(policy.records.borrow().is_empty());
}

#[test]
fn repeated_branching_cname_links_have_bounded_total_traversal() {
    let qname = name("budget-0.example");
    let question_bytes = question(&qname, TYPE_A);
    let mut answers = Vec::new();

    // Repeating each edge creates an exponentially large number of paths for
    // a depth-bounded recursive walk, despite only a few hundred wire records.
    // The first path remains valid and must still reach the terminal address.
    for index in 0..8 {
        for _ in 0..32 {
            answers.push(cname_answer(
                &format!("budget-{index}.example"),
                &format!("budget-{}.example", index + 1),
                300,
            ));
        }
    }
    answers.push(named_a_answer("budget-8.example", 300, [203, 0, 113, 12]));
    let message = response(std::slice::from_ref(&question_bytes), &answers);

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 8);
    assert!(records.iter().all(|record| record.2 == "203.0.113.12"));
    assert_eq!(records[0].0, "budget-0.example");
    assert_eq!(records[0].1, "budget-1.example");
    assert_eq!(records[7].0, "budget-7.example");
    assert_eq!(records[7].1, "budget-8.example");
}

/// The parser only ever walks `ancount` answers; it never inspects nscount
/// or arcount, so an EDNS(0) OPT pseudo-record in the additional section
/// (root owner, TYPE=41, arcount=1) must not disturb answer recording. This
/// documents that additional-section content is simply invisible to the
/// parser, and that blanking's unconditional zeroing of bytes 6..12 (the
/// ancount/nscount/arcount block) wipes arcount too even though it was
/// never validated.
#[test]
fn opt_pseudo_record_in_additional_section_does_not_disturb_parsing_or_blanking() {
    let qname_encoded = name("opt.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let question_end = 12 + question_bytes.len();

    let mut message = response(std::slice::from_ref(&question_bytes), &[a_answer(&ptr(12))]);
    message[10..12].copy_from_slice(&1u16.to_be_bytes()); // arcount = 1
    message.push(0); // OPT owner name: root
    message.extend_from_slice(&TYPE_OPT.to_be_bytes());
    message.extend_from_slice(&4096u16.to_be_bytes()); // requestor UDP payload size
    message.extend_from_slice(&0u32.to_be_bytes()); // extended RCODE/version/flags
    message.extend_from_slice(&0u16.to_be_bytes()); // RDLENGTH = 0

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].2, "203.0.113.7");
    drop(records);

    let mut blanked = message;
    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    let outcome = process_response(&mut blanked, &policy);
    assert!(matches!(outcome, Outcome::Blanked { new_len, .. } if new_len == question_end));
    assert_eq!(&blanked[6..12], &[0, 0, 0, 0, 0, 0]);
}

/// An RRSIG (type 46) sitting in the answer section alongside a valid A: the
/// parser has no notion of DNSSEC, so RRSIG is simply an unrecognised type
/// whose RDATA is skipped over like any other non-A/AAAA record.
#[test]
fn rrsig_alongside_a_answer_is_skipped_but_a_is_recorded_and_blanking_works() {
    let qname_encoded = name("sig.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let question_end = 12 + question_bytes.len();

    // Stub RDATA: the parser only reads RDLENGTH and skips the bytes, it
    // never interprets RRSIG's internal fields (type covered, algorithm,
    // labels, expiration/inception, key tag, signer name, signature).
    let rrsig_rdata = vec![0u8; 20];
    let rrsig = answer(&ptr(12), TYPE_RRSIG, 300, &rrsig_rdata);

    let mut message = response(
        std::slice::from_ref(&question_bytes),
        &[a_answer(&ptr(12)), rrsig],
    );

    let policy = TestPolicy::default();
    record_answers(&message, &policy);
    let records = policy.records.borrow();
    assert_eq!(records.len(), 1, "RRSIG must not be recorded as an answer");
    assert_eq!(records[0].2, "203.0.113.7");
    drop(records);

    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    let outcome = process_response(&mut message, &policy);
    assert!(matches!(outcome, Outcome::Blanked { new_len, .. } if new_len == question_end));
    assert_eq!(&message[6..12], &[0, 0, 0, 0, 0, 0]);
}

/// After blanking, the truncated prefix (`msg[..new_len]`) must itself be a
/// self-consistent DNS message: this asserts exactly the header fields the
/// crate writes (`blank_dns_message`) and nothing more. In particular it
/// does not assert RD/RA, which the crate deliberately does not preserve —
/// `blank_dns_message` replaces the whole flags word with `0x8000 | rcode`,
/// so RD/RA/AA/TC/Opcode all read back as zero regardless of the original
/// request's flags.
#[test]
fn blanked_message_is_a_self_consistent_dns_message() {
    let qname_encoded = name("blocked.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let mut message = response(std::slice::from_ref(&question_bytes), &[a_answer(&ptr(12))]);

    let policy = TestPolicy {
        blocked: true,
        blocked_rcode: 3,
        ..TestPolicy::default()
    };
    let outcome = process_response(&mut message, &policy);
    let (new_len, rcode) = match outcome {
        Outcome::Blanked { new_len, rcode, .. } => (new_len, rcode),
        Outcome::Unchanged => panic!("expected Outcome::Blanked"),
    };

    // new_len is exactly the header plus the (untouched) question section.
    assert_eq!(new_len, 12 + question_bytes.len());
    let truncated = &message[..new_len];

    // Exact flags word: QR=1, everything else the crate never sets stays 0,
    // RCODE is the masked policy rcode.
    let flags = u16::from_be_bytes([truncated[2], truncated[3]]);
    assert_eq!(flags, 0x8000u16 | u16::from(rcode));

    // qdcount preserved; ancount/nscount/arcount all zeroed.
    assert_eq!(&truncated[4..6], &1u16.to_be_bytes());
    assert_eq!(&truncated[6..12], &[0, 0, 0, 0, 0, 0]);

    // The question section is untouched byte-for-byte.
    assert_eq!(&truncated[12..], question_bytes.as_slice());
}

/// A UDP-style response with TC (truncation) set whose second answer is cut
/// off mid-RDATA, as happens when a reply exceeds the path MTU. Per the
/// incremental-parse contract, answers validated before the cut are still
/// recorded, and the overall outcome fails open (`Unchanged`, no blanking)
/// rather than acting on a partially-parsed message. The parser does not
/// itself inspect the TC bit; the fail-open behaviour comes entirely from
/// the length check on the truncated second record.
#[test]
fn tc_bit_set_and_truncated_second_answer_fails_open_but_records_first_answer() {
    let qname_encoded = name("tc.example");
    let question_bytes = question(&qname_encoded, TYPE_A);
    let full = response(
        std::slice::from_ref(&question_bytes),
        &[
            a_answer(&ptr(12)),
            answer(&ptr(12), TYPE_A, 300, &[198, 51, 100, 9]),
        ],
    );
    // Cut off the last 2 of the second answer's 4 RDATA bytes.
    let cut_at = full.len() - 2;
    let mut message = full[..cut_at].to_vec();
    // Set TC (bit 1 of the flags' high byte) on top of the standard
    // QR|RD|RA flags `response` already set.
    message[2] |= 0x02;

    let policy = TestPolicy {
        blocked: true,
        ..TestPolicy::default()
    };
    let outcome = process_response(&mut message, &policy);
    assert_eq!(outcome, Outcome::Unchanged);
    let records = policy.records.borrow();
    assert_eq!(
        records.len(),
        1,
        "the answer before the cut is still recorded"
    );
    assert_eq!(records[0].2, "203.0.113.7");
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

/// A frame whose length prefix was split across reads is handled by the
/// partial path even when its whole payload is present. The decision there
/// must not be weaker than the complete path's, so an SVCB/HTTPS answer still
/// blanks a domain the policy does not block.
#[test]
fn partial_response_blanks_svcb_when_answers_are_complete() {
    let qname = name("cdn.example");
    let question_bytes = question(&qname, TYPE_A);
    let question_end = 12 + question_bytes.len();
    let mut message = response(
        std::slice::from_ref(&question_bytes),
        &[answer(&[0xc0, 12], TYPE_HTTPS, 300, &[0, 1, 0])],
    );
    let policy = TestPolicy::default(); // not blocked
    let outcome = process_partial_response(&mut message, &policy);

    assert!(
        matches!(outcome, Outcome::Blanked { new_len, .. } if new_len == question_end),
        "SVCB answer must blank even when the domain is not blocked"
    );
    assert!(message[question_end..].iter().all(|byte| *byte == 0));
    // Same verdict as the complete path would reach.
    let mut twin = response(
        std::slice::from_ref(&question_bytes),
        &[answer(&[0xc0, 12], TYPE_HTTPS, 300, &[0, 1, 0])],
    );
    assert!(matches!(
        process_response(&mut twin, &TestPolicy::default()),
        Outcome::Blanked { .. }
    ));
}

/// The truncated fallback loses only the answer-derived signals: an SVCB
/// answer cut short by the read boundary can no longer be detected, so an
/// unblocked domain passes through untouched.
#[test]
fn partial_response_cannot_detect_svcb_past_the_truncation_point() {
    let qname = name("cdn.example");
    let question_bytes = question(&qname, TYPE_A);
    let question_end = 12 + question_bytes.len();
    let full = response(
        std::slice::from_ref(&question_bytes),
        &[answer(&[0xc0, 12], TYPE_HTTPS, 300, &[0, 1, 0])],
    );
    let mut message = full[..question_end + 6].to_vec();
    let original = message.clone();
    let policy = TestPolicy::default(); // not blocked

    assert_eq!(
        process_partial_response(&mut message, &policy),
        Outcome::Unchanged
    );
    assert_eq!(message, original);
}

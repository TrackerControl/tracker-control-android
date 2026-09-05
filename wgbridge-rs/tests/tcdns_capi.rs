use std::ffi::{c_char, c_void, CStr};
use std::mem::{offset_of, size_of};

use wgbridge::tcdns_capi::{
    tcdns_abi_version, tcdns_process_partial_response, tcdns_process_response, BlockedRcode,
    IsDomainBlocked, OnBlanked, RecordAnswer, TcdnsCallbacks, TCDNS_ABI_VERSION, TCDNS_UNCHANGED,
};

const TYPE_A: u16 = 1;
const TYPE_CNAME: u16 = 5;
const TYPE_HTTPS: u16 = 65;
const CLASS_IN: u16 = 1;

#[derive(Default)]
struct Capture {
    records: Vec<(String, String, String, i32)>,
    blanked: Vec<(String, u16, u8)>,
    blocked: bool,
}

unsafe extern "C" fn record_answer(
    ctx: *mut c_void,
    qname: *const c_char,
    aname: *const c_char,
    resource: *const c_char,
    ttl: i32,
) {
    // SAFETY: tests pass a valid Capture pointer and callback-duration C
    // strings from the library.
    let capture = unsafe { &mut *(ctx.cast::<Capture>()) };
    let qname = unsafe { CStr::from_ptr(qname) }
        .to_string_lossy()
        .into_owned();
    let aname = unsafe { CStr::from_ptr(aname) }
        .to_string_lossy()
        .into_owned();
    let resource = unsafe { CStr::from_ptr(resource) }
        .to_string_lossy()
        .into_owned();
    capture.records.push((qname, aname, resource, ttl));
}

unsafe extern "C" fn is_domain_blocked(ctx: *mut c_void, qname: *const c_char) -> i32 {
    // SAFETY: tests pass a valid Capture pointer and callback-duration C string.
    let capture = unsafe { &mut *(ctx.cast::<Capture>()) };
    let qname = unsafe { CStr::from_ptr(qname) };
    assert!(!qname.to_bytes().contains(&0));
    i32::from(capture.blocked)
}

unsafe extern "C" fn blocked_rcode(_ctx: *mut c_void) -> u8 {
    0x1f
}

unsafe extern "C" fn on_blanked(ctx: *mut c_void, qname: *const c_char, qtype: u16, rcode: u8) {
    // SAFETY: tests pass a valid Capture pointer and callback-duration C string.
    let capture = unsafe { &mut *(ctx.cast::<Capture>()) };
    let qname = unsafe { CStr::from_ptr(qname) }
        .to_string_lossy()
        .into_owned();
    capture.blanked.push((qname, qtype, rcode));
}

fn callbacks() -> TcdnsCallbacks {
    TcdnsCallbacks {
        abi_version: TCDNS_ABI_VERSION,
        record_answer: Some(record_answer as RecordAnswer),
        is_domain_blocked: Some(is_domain_blocked as IsDomainBlocked),
        blocked_rcode: Some(blocked_rcode as BlockedRcode),
        on_blanked: Some(on_blanked as OnBlanked),
        log: None,
    }
}

fn question_name(name: &str) -> Vec<u8> {
    let mut result = Vec::new();
    for label in name.split('.') {
        result.push(label.len() as u8);
        result.extend_from_slice(label.as_bytes());
    }
    result.push(0);
    result
}

fn question(name: &[u8]) -> Vec<u8> {
    let mut result = name.to_vec();
    result.extend_from_slice(&TYPE_A.to_be_bytes());
    result.extend_from_slice(&CLASS_IN.to_be_bytes());
    result
}

fn answer(name: &[u8], qtype: u16, rdata: &[u8]) -> Vec<u8> {
    let mut result = name.to_vec();
    result.extend_from_slice(&qtype.to_be_bytes());
    result.extend_from_slice(&CLASS_IN.to_be_bytes());
    result.extend_from_slice(&300u32.to_be_bytes());
    result.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
    result.extend_from_slice(rdata);
    result
}

fn cname_answer(owner: &str, target: &str) -> Vec<u8> {
    let owner = question_name(owner);
    let target = question_name(target);
    answer(&owner, TYPE_CNAME, &target)
}

fn response(question_name: &[u8], answers: &[Vec<u8>]) -> Vec<u8> {
    let mut result = vec![0u8; 12];
    result[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
    result[4..6].copy_from_slice(&1u16.to_be_bytes());
    result[6..8].copy_from_slice(&(answers.len() as u16).to_be_bytes());
    result.extend_from_slice(&question(question_name));
    for answer in answers {
        result.extend_from_slice(answer);
    }
    result
}

fn svcb_response() -> Vec<u8> {
    let name = question_name("tracker.example");
    response(
        &name,
        &[
            answer(&[0xc0, 12], TYPE_A, &[203, 0, 113, 7]),
            answer(&[0xc0, 12], TYPE_HTTPS, &[]),
        ],
    )
}

#[test]
fn abi_layout_and_version_are_stable() {
    assert_eq!(tcdns_abi_version(), TCDNS_ABI_VERSION);
    assert_eq!(offset_of!(TcdnsCallbacks, abi_version), 0);
    assert_eq!(offset_of!(TcdnsCallbacks, record_answer), 8);
    assert_eq!(offset_of!(TcdnsCallbacks, is_domain_blocked), 16);
    assert_eq!(offset_of!(TcdnsCallbacks, blocked_rcode), 24);
    assert_eq!(offset_of!(TcdnsCallbacks, on_blanked), 32);
    assert_eq!(offset_of!(TcdnsCallbacks, log), 40);
    assert_eq!(size_of::<TcdnsCallbacks>(), 48);
}

#[test]
fn null_and_invalid_callback_tables_are_unchanged() {
    assert_eq!(
        unsafe {
            tcdns_process_response(
                std::ptr::null_mut(),
                0,
                std::ptr::null(),
                std::ptr::null_mut(),
            )
        },
        TCDNS_UNCHANGED
    );
    assert_eq!(
        unsafe {
            tcdns_process_response(
                std::ptr::null_mut(),
                1,
                std::ptr::null(),
                std::ptr::null_mut(),
            )
        },
        TCDNS_UNCHANGED
    );

    let mut message = svcb_response();
    let original = message.clone();
    let mut capture = Capture::default();
    let mut invalid = callbacks();
    invalid.abi_version = TCDNS_ABI_VERSION + 1;
    assert_eq!(
        unsafe {
            tcdns_process_response(
                message.as_mut_ptr(),
                message.len(),
                &invalid,
                (&mut capture as *mut Capture).cast(),
            )
        },
        TCDNS_UNCHANGED
    );
    assert_eq!(message, original);

    for missing in 0..4 {
        let mut invalid = callbacks();
        match missing {
            0 => invalid.record_answer = None,
            1 => invalid.is_domain_blocked = None,
            2 => invalid.blocked_rcode = None,
            _ => invalid.on_blanked = None,
        }
        let mut message = svcb_response();
        assert_eq!(
            unsafe {
                tcdns_process_response(
                    message.as_mut_ptr(),
                    message.len(),
                    &invalid,
                    (&mut capture as *mut Capture).cast(),
                )
            },
            TCDNS_UNCHANGED
        );
    }
}

#[test]
fn callbacks_receive_terminated_strings_and_new_length() {
    let raw_name = [3u8, 0xff, 0, b'a', 0];
    let mut message = vec![0u8; 12];
    message[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
    message[4..6].copy_from_slice(&1u16.to_be_bytes());
    message[6..8].copy_from_slice(&2u16.to_be_bytes());
    message.extend_from_slice(&question(&raw_name));
    let question_end = message.len();
    message.extend_from_slice(&answer(&[0xc0, 12], TYPE_A, &[203, 0, 113, 7]));
    message.extend_from_slice(&answer(&[0xc0, 12], TYPE_HTTPS, &[]));

    let mut capture = Capture {
        blocked: false,
        ..Capture::default()
    };
    let callbacks = callbacks();
    let new_len = unsafe {
        tcdns_process_response(
            message.as_mut_ptr(),
            message.len(),
            &callbacks,
            (&mut capture as *mut Capture).cast(),
        )
    };
    assert_eq!(new_len, question_end);
    assert_eq!(capture.records.len(), 1);
    assert_eq!(capture.records[0].0, "��a");
    assert_eq!(capture.records[0].1, "��a");
    assert!(capture
        .blanked
        .iter()
        .all(|(name, _, _)| !name.as_bytes().contains(&0)));
    assert_eq!(capture.blanked, vec![("��a".to_owned(), TYPE_A, 0x0f)]);
}

#[test]
fn capi_receives_each_validated_cname_link_without_terminal_self_row() {
    let qname = question_name("alias.example");
    let mut message = response(
        &qname,
        &[
            answer(
                &question_name("terminal.example"),
                TYPE_A,
                &[203, 0, 113, 12],
            ),
            cname_answer("alias.example", "terminal.example"),
        ],
    );
    let mut capture = Capture::default();
    let callbacks = callbacks();
    let result = unsafe {
        tcdns_process_response(
            message.as_mut_ptr(),
            message.len(),
            &callbacks,
            (&mut capture as *mut Capture).cast(),
        )
    };

    assert_eq!(result, TCDNS_UNCHANGED);
    assert_eq!(
        capture.records,
        vec![(
            "alias.example".to_owned(),
            "terminal.example".to_owned(),
            "203.0.113.12".to_owned(),
            300,
        )]
    );
}

#[test]
fn partial_response_preserves_buffer_length_and_reports_blanking() {
    let raw_name = question_name("tracker.example");
    let question_end = 12 + question(&raw_name).len();
    let first_answer = answer(&[0xc0, 12], TYPE_A, &[203, 0, 113, 7]);
    let mut message = response(
        &raw_name,
        &[first_answer.clone(), answer(&[0xc0, 12], TYPE_HTTPS, &[])],
    );
    message.truncate(question_end + first_answer.len() + 5);
    let original_len = message.len();
    let mut capture = Capture {
        blocked: true,
        ..Capture::default()
    };
    let callbacks = callbacks();
    let result = unsafe {
        tcdns_process_partial_response(
            message.as_mut_ptr(),
            message.len(),
            &callbacks,
            (&mut capture as *mut Capture).cast(),
        )
    };

    assert_eq!(result, question_end);
    assert_eq!(message.len(), original_len);
    assert!(message[question_end..].iter().all(|byte| *byte == 0));
    assert_eq!(capture.records.len(), 1);
    assert_eq!(
        capture.blanked,
        vec![("tracker.example".to_owned(), TYPE_A, 0x0f)]
    );
}

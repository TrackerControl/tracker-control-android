#![cfg_attr(
    not(test),
    deny(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing
    )
)]

mod message;
pub mod policy;

pub use policy::DnsPolicy;

/// The result of applying the response policy to a bare DNS message.
#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    Unchanged,
    Blanked {
        new_len: usize,
        qname: String,
        qtype: u16,
        rcode: u8,
    },
}

/// Records valid A and AAAA answers from a DNS response. Malformed input is
/// ignored. A sink must not panic; this function deliberately does not catch
/// panics because the Android release profile uses `panic = "abort"`.
pub fn record_answers(msg: &[u8], policy: &dyn DnsPolicy) {
    let _ = message::parse_answers_incrementally(msg, |answer| {
        policy.record_answer(&answer.qname, &answer.aname, &answer.resource, answer.ttl);
    });
}

/// Applies blanking policy to a bare DNS message without recording answers.
pub fn apply_policy(msg: &mut [u8], policy: &dyn DnsPolicy) -> Outcome {
    let Some(parsed) = message::parse_message(msg) else {
        return Outcome::Unchanged;
    };
    apply_parsed(msg, parsed, policy)
}

/// Records valid answers and applies blanking policy in one parse. Recording
/// happens before any blanking so address mappings survive policy rewrites.
pub fn process_response(msg: &mut [u8], policy: &dyn DnsPolicy) -> Outcome {
    let Some(parsed) = message::parse_answers_incrementally(msg, |answer| {
        policy.record_answer(&answer.qname, &answer.aname, &answer.resource, answer.ttl);
    }) else {
        return Outcome::Unchanged;
    };
    apply_parsed(msg, parsed, policy)
}

fn apply_parsed(msg: &mut [u8], parsed: message::DnsMessage, policy: &dyn DnsPolicy) -> Outcome {
    // An empty root qname is valid DNS syntax but is not a policy subject.
    // In particular, do not call a Java-backed policy with an empty string.
    if parsed.qname.is_empty() {
        return Outcome::Unchanged;
    }
    if !parsed.contains_svcb && !policy.is_domain_blocked(&parsed.qname) {
        return Outcome::Unchanged;
    }
    let rcode = policy.blocked_rcode() & 0x0f;
    message::blank_dns_message(msg, rcode);
    Outcome::Blanked {
        new_len: parsed.question_end,
        qname: parsed.qname,
        qtype: parsed.qtype,
        rcode,
    }
}

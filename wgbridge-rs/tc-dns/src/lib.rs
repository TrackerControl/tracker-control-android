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

/// Applies blocking policy to the visible prefix of a DNS-over-TCP response
/// whose remainder may not have been read yet, mutating it in place without
/// ever shortening it: earlier bytes of the frame are already on the wire, so
/// its length can no longer change.
///
/// When the answer section happens to be complete the decision is exactly the
/// one `process_response` would make, SVCB included. When it is truncated the
/// decision falls back to the question section alone: answers validated before
/// the truncation point are still recorded, but SVCB/HTTPS records cannot be
/// detected, so SVCB-triggered blanking is unavailable for such frames.
///
/// On a blanking hit the bytes after the question are zeroed, so a client that
/// ignores the cleared `ancount` cannot recover the original answers. The
/// returned `new_len` reports where the message proper ends; the caller must
/// keep forwarding the original length.
pub fn process_partial_response(msg: &mut [u8], policy: &dyn DnsPolicy) -> Outcome {
    let parsed = message::parse_answers_incrementally(msg, |answer| {
        policy.record_answer(&answer.qname, &answer.aname, &answer.resource, answer.ttl);
    });

    // A fully parsed message is decided exactly as the complete path decides
    // it; only a truncated one loses the answer-derived signals.
    let (qname, qtype, question_end, contains_svcb) = match parsed {
        Some(parsed) => (
            parsed.qname,
            parsed.qtype,
            parsed.question_end,
            parsed.contains_svcb,
        ),
        None => {
            let Some(layout) = message::read_question_layout(msg) else {
                return Outcome::Unchanged;
            };
            (layout.qname, layout.qtype, layout.question_end, false)
        }
    };

    // An empty root qname is valid DNS syntax but is not a policy subject.
    // In particular, do not call a Java-backed policy with an empty string.
    if qname.is_empty() {
        return Outcome::Unchanged;
    }
    if !contains_svcb && !policy.is_domain_blocked(&qname) {
        return Outcome::Unchanged;
    }

    let rcode = policy.blocked_rcode() & 0x0f;
    message::blank_dns_message(msg, rcode);
    if let Some(tail) = msg.get_mut(question_end..) {
        tail.fill(0);
    }
    Outcome::Blanked {
        new_len: question_end,
        qname,
        qtype,
        rcode,
    }
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

#[cfg(feature = "capi")]
pub mod capi;

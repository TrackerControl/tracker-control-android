use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, Ipv6Addr};

pub(crate) const DNS_TYPE_A: u16 = 1;
pub(crate) const DNS_TYPE_AAAA: u16 = 28;
pub(crate) const DNS_TYPE_CNAME: u16 = 5;
pub(crate) const DNS_CLASS_IN: u16 = 1;
pub(crate) const DNS_TYPE_SVCB: u16 = 64;
pub(crate) const DNS_TYPE_HTTPS: u16 = 65;
pub(crate) const DNS_HEADER_LEN: usize = 12;
const MAX_NAME_DEPTH: u32 = 8;
const MAX_NAME_LABELS: usize = 128;
const MAX_NAME_OCTETS: usize = 255;
const MAX_CNAME_CHAIN_DEPTH: usize = 8;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DnsAnswer {
    pub(crate) qname: String,
    pub(crate) aname: String,
    pub(crate) resource: String,
    pub(crate) ttl: i32,
}

#[derive(Debug, Clone)]
enum ParsedAnswer {
    Cname(CnameRecord),
    Address(AddressRecord),
}

#[derive(Debug, Clone)]
struct CnameRecord {
    owner: String,
    target: String,
    ttl: i32,
}

#[derive(Debug, Clone)]
struct AddressRecord {
    owner: String,
    resource: String,
    ttl: i32,
}

#[derive(Debug)]
pub(crate) struct DnsMessage {
    pub(crate) qname: String,
    pub(crate) qtype: u16,
    pub(crate) question_end: usize,
    pub(crate) contains_svcb: bool,
}

#[derive(Debug)]
pub(crate) struct QuestionLayout {
    pub(crate) qname: String,
    pub(crate) qtype: u16,
    pub(crate) question_end: usize,
    pub(crate) ancount: usize,
    pub(crate) answer_offset: usize,
}

fn read_u16(msg: &[u8], offset: usize) -> Option<u16> {
    let bytes = msg.get(offset..offset.checked_add(2)?)?;
    Some(u16::from_be_bytes([*bytes.first()?, *bytes.get(1)?]))
}

fn read_u32(msg: &[u8], offset: usize) -> Option<u32> {
    let bytes = msg.get(offset..offset.checked_add(4)?)?;
    Some(u32::from_be_bytes([
        *bytes.first()?,
        *bytes.get(1)?,
        *bytes.get(2)?,
        *bytes.get(3)?,
    ]))
}

pub(crate) fn read_question_layout(msg: &[u8]) -> Option<QuestionLayout> {
    if msg.len() < DNS_HEADER_LEN {
        return None;
    }
    let flags = read_u16(msg, 2)?;
    if flags & 0x8000 == 0 || flags & 0x7800 != 0 {
        return None;
    }
    let qdcount = usize::from(read_u16(msg, 4)?);
    let ancount = usize::from(read_u16(msg, 6)?);
    if qdcount == 0 || ancount == 0 {
        return None;
    }

    let mut offset = DNS_HEADER_LEN;
    let mut qname = None;
    let mut qtype = 0;
    for question in 0..qdcount {
        let (name, name_end) = read_dns_name(msg, offset, 0)?;
        let question_end = name_end.checked_add(4)?;
        if question_end > msg.len() {
            return None;
        }
        if question == 0 {
            qname = Some(canonicalize_name(&name));
            qtype = read_u16(msg, name_end)?;
        }
        offset = question_end;
    }
    Some(QuestionLayout {
        qname: qname?,
        qtype,
        question_end: offset,
        ancount,
        answer_offset: offset,
    })
}

pub(crate) fn parse_message(msg: &[u8]) -> Option<DnsMessage> {
    let layout = read_question_layout(msg)?;
    let mut offset = layout.answer_offset;
    let mut contains_svcb = false;
    for _ in 0..layout.ancount {
        let (answer, answer_end, is_svcb) = parse_answer(msg, offset)?;
        contains_svcb |= is_svcb;
        let _ = answer;
        offset = answer_end;
    }
    Some(DnsMessage {
        qname: layout.qname,
        qtype: layout.qtype,
        question_end: layout.question_end,
        contains_svcb,
    })
}

/// Parses the question section and then answers one at a time. A malformed
/// later answer returns `None`, so callers can retain records already emitted
/// from earlier answers while refusing to blank a partially validated message.
///
/// `process_partial_response` is the one caller that does not honour that
/// refusal: on a DNS-over-TCP frame the visible prefix is expected to end
/// mid-answer, so `None` there carries no signal about whether the bytes are
/// malformed or merely unread, and it falls back to the question section. See
/// its documentation for why that is safe.
pub(crate) fn parse_answers_incrementally(
    msg: &[u8],
    mut on_answer: impl FnMut(&DnsAnswer),
) -> Option<DnsMessage> {
    let layout = read_question_layout(msg)?;
    if layout.qname.is_empty() {
        return Some(DnsMessage {
            qname: layout.qname,
            qtype: layout.qtype,
            question_end: layout.question_end,
            contains_svcb: false,
        });
    }
    let mut offset = layout.answer_offset;
    let mut contains_svcb = false;
    let mut records = Vec::new();
    let mut complete = true;
    for _ in 0..layout.ancount {
        let Some((answer, answer_end, is_svcb)) = parse_answer(msg, offset) else {
            complete = false;
            break;
        };
        contains_svcb |= is_svcb;
        if let Some(answer) = answer {
            records.push(answer);
        }
        offset = answer_end;
    }
    emit_answers(&layout.qname, &records, &mut on_answer);
    if !complete {
        return None;
    }
    Some(DnsMessage {
        qname: layout.qname,
        qtype: layout.qtype,
        question_end: layout.question_end,
        contains_svcb,
    })
}

fn parse_answer(msg: &[u8], offset: usize) -> Option<(Option<ParsedAnswer>, usize, bool)> {
    let (aname, name_end) = read_dns_name(msg, offset, 0)?;
    let aname = canonicalize_name(&aname);
    let fixed_end = name_end.checked_add(10)?;
    if fixed_end > msg.len() {
        return None;
    }
    let typ = read_u16(msg, name_end)?;
    let class = read_u16(msg, name_end.checked_add(2)?)?;
    let ttl = read_u32(msg, name_end.checked_add(4)?)?;
    let rdlen = usize::from(read_u16(msg, name_end.checked_add(8)?)?);
    let rdata_end = fixed_end.checked_add(rdlen)?;
    if rdata_end > msg.len() {
        return None;
    }
    let rdata = msg.get(fixed_end..rdata_end)?;
    let is_svcb = class == DNS_CLASS_IN && (typ == DNS_TYPE_SVCB || typ == DNS_TYPE_HTTPS);
    let answer = if class != DNS_CLASS_IN {
        None
    } else {
        match typ {
            DNS_TYPE_A if rdlen == 4 => Some(ParsedAnswer::Address(AddressRecord {
                owner: aname,
                resource: Ipv4Addr::new(
                    *rdata.first()?,
                    *rdata.get(1)?,
                    *rdata.get(2)?,
                    *rdata.get(3)?,
                )
                .to_string(),
                ttl: clamp_ttl(ttl),
            })),
            DNS_TYPE_AAAA if rdlen == 16 => {
                let mut ip = [0u8; 16];
                ip.copy_from_slice(rdata);
                Some(ParsedAnswer::Address(AddressRecord {
                    owner: aname,
                    resource: Ipv6Addr::from(ip).to_string(),
                    ttl: clamp_ttl(ttl),
                }))
            }
            DNS_TYPE_CNAME => {
                let Some((target, target_end)) = read_dns_name(msg, fixed_end, 0) else {
                    return Some((None, rdata_end, is_svcb));
                };
                if target_end != rdata_end {
                    return Some((None, rdata_end, is_svcb));
                }
                Some(ParsedAnswer::Cname(CnameRecord {
                    owner: aname,
                    target: canonicalize_name(&target),
                    ttl: clamp_ttl(ttl),
                }))
            }
            _ => None,
        }
    };
    Some((answer, rdata_end, is_svcb))
}

fn emit_answers(qname: &str, records: &[ParsedAnswer], mut on_answer: impl FnMut(&DnsAnswer)) {
    let mut cname_links: HashMap<String, Vec<CnameRecord>> = HashMap::new();
    let mut addresses_by_owner: HashMap<String, Vec<AddressRecord>> = HashMap::new();
    let mut addresses = Vec::new();
    for record in records {
        match record {
            ParsedAnswer::Cname(link) => {
                cname_links
                    .entry(link.owner.clone())
                    .or_default()
                    .push(link.clone());
            }
            ParsedAnswer::Address(address) => {
                addresses.push(address.clone());
                addresses_by_owner
                    .entry(address.owner.clone())
                    .or_default()
                    .push(address.clone());
            }
        }
    }

    let mut answers = Vec::new();
    let mut indexes = HashMap::new();
    if cname_links.is_empty() {
        // Keep the historic behaviour for an ordinary response: every valid
        // address answer is attributed to the question, even if its owner is
        // an unusual (but syntactically valid) name.
        for address in addresses {
            push_answer(
                &mut answers,
                &mut indexes,
                qname,
                &address.owner,
                &address.resource,
                address.ttl,
            );
        }
    } else {
        // Once CNAMEs are present, only a path rooted at the question can
        // attribute an address. This keeps unrelated answer-section records
        // from being attached to the original question.
        let mut path = Vec::new();
        let mut visited = HashSet::new();
        collect_chain(
            qname,
            0,
            &mut path,
            &mut visited,
            &cname_links,
            &addresses_by_owner,
            &mut answers,
            &mut indexes,
        );
    }

    for answer in answers {
        on_answer(&answer);
    }
}

fn collect_chain(
    name: &str,
    depth: usize,
    path: &mut Vec<CnameRecord>,
    visited: &mut HashSet<String>,
    cname_links: &HashMap<String, Vec<CnameRecord>>,
    addresses_by_owner: &HashMap<String, Vec<AddressRecord>>,
    answers: &mut Vec<DnsAnswer>,
    indexes: &mut HashMap<(String, String, String), usize>,
) {
    if !visited.insert(name.to_owned()) {
        return;
    }

    // An address is terminal only when this owner has no further CNAME hop.
    // Treating an address and an outgoing CNAME at the same owner as a
    // terminal would make malformed loops look like validated chains.
    if !cname_links.contains_key(name) {
        if let Some(addresses) = addresses_by_owner.get(name) {
            for address in addresses {
                if path.is_empty() {
                    // No CNAME path means this is the historic direct
                    // qname/aname attribution, even when another answer
                    // carries an unrelated CNAME.
                    push_answer(answers, indexes, name, name, &address.resource, address.ttl);
                } else {
                    // Each edge gets the minimum TTL over its remaining path.
                    // The terminal owner itself is represented by the final
                    // edge's aname, avoiding a synthetic self-row.
                    for (index, link) in path.iter().enumerate() {
                        let mut ttl = address.ttl;
                        for suffix_link in path.iter().skip(index) {
                            ttl = ttl.min(suffix_link.ttl);
                        }
                        push_answer(
                            answers,
                            indexes,
                            &link.owner,
                            &link.target,
                            &address.resource,
                            ttl,
                        );
                    }
                }
            }
        }
    }

    if depth < MAX_CNAME_CHAIN_DEPTH {
        if let Some(links) = cname_links.get(name) {
            for link in links {
                path.push(link.clone());
                collect_chain(
                    &link.target,
                    depth + 1,
                    path,
                    visited,
                    cname_links,
                    addresses_by_owner,
                    answers,
                    indexes,
                );
                let _ = path.pop();
            }
        }
    }
    visited.remove(name);
}

fn push_answer(
    answers: &mut Vec<DnsAnswer>,
    indexes: &mut HashMap<(String, String, String), usize>,
    qname: &str,
    aname: &str,
    resource: &str,
    ttl: i32,
) {
    if qname.is_empty() || aname.is_empty() {
        return;
    }
    let key = (qname.to_owned(), aname.to_owned(), resource.to_owned());
    if let Some(index) = indexes.get(&key).copied() {
        if let Some(answer) = answers.get_mut(index) {
            answer.ttl = answer.ttl.min(ttl);
        }
        return;
    }
    let index = answers.len();
    answers.push(DnsAnswer {
        qname: key.0.clone(),
        aname: key.1.clone(),
        resource: key.2.clone(),
        ttl,
    });
    indexes.insert(key, index);
}

fn canonicalize_name(name: &str) -> String {
    name.to_ascii_lowercase()
}

fn clamp_ttl(ttl: u32) -> i32 {
    ttl.min(i32::MAX as u32) as i32
}

/// Reads a possibly compressed DNS name. `next` is the byte after the encoded
/// name in the current message, so a label sequence followed by a pointer
/// resumes at the pointer's end (`off + 2`), not at the pointer target.
pub(crate) fn read_dns_name(msg: &[u8], start: usize, depth: u32) -> Option<(String, usize)> {
    let mut state = NameState {
        labels: 0,
        encoded_octets: 0,
    };
    let (labels, next) = read_dns_name_inner(msg, start, depth, &mut state)?;
    Some((labels.join("."), next))
}

struct NameState {
    labels: usize,
    encoded_octets: usize,
}

fn read_dns_name_inner(
    msg: &[u8],
    start: usize,
    depth: u32,
    state: &mut NameState,
) -> Option<(Vec<String>, usize)> {
    if depth > MAX_NAME_DEPTH || start >= msg.len() {
        return None;
    }
    let mut labels = Vec::new();
    let mut offset = start;
    loop {
        let length = usize::from(*msg.get(offset)?);
        match length & 0xc0 {
            0xc0 => {
                let pointer_end = offset.checked_add(2)?;
                if pointer_end > msg.len() {
                    return None;
                }
                // The pointer itself contributes nothing to the
                // uncompressed-name length: RFC 1035's 255-octet cap
                // applies to the expanded name, not the wire encoding, so
                // only label octets (below, the 0x00 arm) count toward
                // `MAX_NAME_OCTETS`. Pointer-chasing is bounded separately
                // by `MAX_NAME_DEPTH` and the bounds check above.
                let ptr = ((length & 0x3f) << 8) | usize::from(*msg.get(offset + 1)?);
                let (pointer_labels, _) = read_dns_name_inner(msg, ptr, depth + 1, state)?;
                labels.extend(pointer_labels);
                return Some((labels, pointer_end));
            }
            0x00 => {
                state.encoded_octets = state.encoded_octets.checked_add(1)?;
                if state.encoded_octets > MAX_NAME_OCTETS {
                    return None;
                }
                if length == 0 {
                    return Some((labels, offset.checked_add(1)?));
                }
                if length > 63 {
                    return None;
                }
                let label_start = offset.checked_add(1)?;
                let label_end = label_start.checked_add(length)?;
                if label_end > msg.len() {
                    return None;
                }
                state.encoded_octets = state.encoded_octets.checked_add(length)?;
                if state.encoded_octets > MAX_NAME_OCTETS || state.labels >= MAX_NAME_LABELS {
                    return None;
                }
                state.labels += 1;
                labels.push(decode_label(msg.get(label_start..label_end)?));
                offset = label_end;
            }
            _ => return None,
        }
    }
}

fn decode_label(label: &[u8]) -> String {
    String::from_utf8_lossy(label)
        .chars()
        .map(|character| {
            // JNI's NewStringUTF consumes modified UTF-8. Supplementary
            // scalar values require surrogate-pair encoding there, so keep
            // the C-ABI contract safe by replacing them alongside NUL.
            if character == '\0' || character > '\u{ffff}' {
                '\u{fffd}'
            } else {
                character
            }
        })
        .collect()
}

pub(crate) fn blank_dns_message(msg: &mut [u8], rcode: u8) {
    if let Some(flags) = msg.get_mut(2..4) {
        flags.copy_from_slice(&(0x8000u16 | u16::from(rcode & 0x0f)).to_be_bytes());
    }
    if let Some(counts) = msg.get_mut(6..12) {
        counts.fill(0);
    }
}

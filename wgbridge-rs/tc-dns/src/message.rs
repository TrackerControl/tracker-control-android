use std::net::{Ipv4Addr, Ipv6Addr};

pub(crate) const DNS_TYPE_A: u16 = 1;
pub(crate) const DNS_TYPE_AAAA: u16 = 28;
pub(crate) const DNS_CLASS_IN: u16 = 1;
pub(crate) const DNS_TYPE_SVCB: u16 = 64;
pub(crate) const DNS_TYPE_HTTPS: u16 = 65;
pub(crate) const DNS_HEADER_LEN: usize = 12;
const MAX_NAME_DEPTH: u32 = 8;
const MAX_NAME_LABELS: usize = 128;
const MAX_NAME_OCTETS: usize = 255;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DnsAnswer {
    pub(crate) qname: String,
    pub(crate) aname: String,
    pub(crate) resource: String,
    pub(crate) ttl: i32,
}

#[derive(Debug)]
pub(crate) struct DnsMessage {
    pub(crate) qname: String,
    pub(crate) qtype: u16,
    pub(crate) question_end: usize,
    pub(crate) contains_svcb: bool,
}

#[derive(Debug)]
struct QuestionLayout {
    qname: String,
    qtype: u16,
    question_end: usize,
    ancount: usize,
    answer_offset: usize,
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

fn read_question_layout(msg: &[u8]) -> Option<QuestionLayout> {
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
            qname = Some(name);
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
        let (answer, answer_end, is_svcb) = parse_answer(msg, offset, &layout.qname)?;
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
    for _ in 0..layout.ancount {
        let (answer, answer_end, is_svcb) = parse_answer(msg, offset, &layout.qname)?;
        contains_svcb |= is_svcb;
        if let Some(answer) = answer {
            on_answer(&answer);
        }
        offset = answer_end;
    }
    Some(DnsMessage {
        qname: layout.qname,
        qtype: layout.qtype,
        question_end: layout.question_end,
        contains_svcb,
    })
}

fn parse_answer(
    msg: &[u8],
    offset: usize,
    qname: &str,
) -> Option<(Option<DnsAnswer>, usize, bool)> {
    let (aname, name_end) = read_dns_name(msg, offset, 0)?;
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
            DNS_TYPE_A if rdlen == 4 => Some(DnsAnswer {
                qname: qname.to_owned(),
                aname,
                resource: Ipv4Addr::new(
                    *rdata.first()?,
                    *rdata.get(1)?,
                    *rdata.get(2)?,
                    *rdata.get(3)?,
                )
                .to_string(),
                ttl: clamp_ttl(ttl),
            }),
            DNS_TYPE_AAAA if rdlen == 16 => {
                let mut ip = [0u8; 16];
                ip.copy_from_slice(rdata);
                Some(DnsAnswer {
                    qname: qname.to_owned(),
                    aname,
                    resource: Ipv6Addr::from(ip).to_string(),
                    ttl: clamp_ttl(ttl),
                })
            }
            _ => None,
        }
    };
    Some((answer, rdata_end, is_svcb))
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

//! Passive DNS inspection of decrypted inbound packets (port of the old Go
//! wgbridge/dns.go). Extracts A/AAAA answers from UDP:53 responses and hands
//! them to the [`DnsSink`]; packets are never modified or blocked here.

use std::net::{Ipv4Addr, Ipv6Addr};
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::callbacks::DnsSink;

const DNS_TYPE_A: u16 = 1;
const DNS_TYPE_AAAA: u16 = 28;
const DNS_CLASS_IN: u16 = 1;

const IP_PROTO_HOP_BY_HOP: u8 = 0;
const IP_PROTO_UDP: u8 = 17;
const IP_PROTO_ROUTING: u8 = 43;
const IP_PROTO_FRAGMENT: u8 = 44;
const IP_PROTO_DST_OPTS: u8 = 60;

pub fn inspect_dns_response(packet: &[u8], recorder: &dyn DnsSink) {
    if packet.is_empty() {
        return;
    }
    let Some(payload) = udp_payload_from_dns_response(packet) else {
        return;
    };
    for rr in parse_dns_answers(payload) {
        // The recorder crosses into Java; never let a failure there take
        // down the packet path.
        let _ = catch_unwind(AssertUnwindSafe(|| {
            recorder.record_dns(&rr.qname, &rr.aname, &rr.resource, rr.ttl);
        }));
    }
}

fn udp_payload_from_dns_response(packet: &[u8]) -> Option<&[u8]> {
    match packet[0] >> 4 {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = ((packet[0] & 0x0f) as usize) * 4;
            if ihl < 20 || packet.len() < ihl + 8 || packet[9] != IP_PROTO_UDP {
                return None;
            }
            let mut total = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            if total == 0 || total > packet.len() {
                total = packet.len();
            }
            udp_dns_payload(&packet[ihl..total])
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let payload_len = u16::from_be_bytes([packet[4], packet[5]]) as usize;
            let mut total = 40 + payload_len;
            if payload_len == 0 || total > packet.len() {
                total = packet.len();
            }
            let mut next = packet[6];
            let mut off = 40usize;
            loop {
                if next == IP_PROTO_UDP {
                    if total < off + 8 {
                        return None;
                    }
                    return udp_dns_payload(&packet[off..total]);
                }
                if next == IP_PROTO_FRAGMENT {
                    return None;
                }
                if !is_ipv6_ext_header(next) || total < off + 2 {
                    return None;
                }
                let hdr_len = (packet[off + 1] as usize + 1) * 8;
                if hdr_len < 8 || total < off + hdr_len {
                    return None;
                }
                next = packet[off];
                off += hdr_len;
            }
        }
        _ => None,
    }
}

fn udp_dns_payload(udp: &[u8]) -> Option<&[u8]> {
    if udp.len() < 8 || u16::from_be_bytes([udp[0], udp[1]]) != 53 {
        return None;
    }
    let udp_len = u16::from_be_bytes([udp[4], udp[5]]) as usize;
    if udp_len < 8 || udp_len > udp.len() {
        return None;
    }
    Some(&udp[8..udp_len])
}

fn is_ipv6_ext_header(next: u8) -> bool {
    next == IP_PROTO_HOP_BY_HOP || next == IP_PROTO_ROUTING || next == IP_PROTO_DST_OPTS
}

#[derive(Debug, PartialEq, Eq)]
struct DnsAnswer {
    qname: String,
    aname: String,
    resource: String,
    ttl: i32,
}

fn parse_dns_answers(msg: &[u8]) -> Vec<DnsAnswer> {
    let mut answers = Vec::new();
    if msg.len() < 12 {
        return answers;
    }
    let flags = u16::from_be_bytes([msg[2], msg[3]]);
    // Must be a response (QR=1) with opcode QUERY.
    if flags & 0x8000 == 0 || flags & 0x7800 != 0 {
        return answers;
    }

    let qdcount = u16::from_be_bytes([msg[4], msg[5]]) as usize;
    let ancount = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    if qdcount == 0 || ancount == 0 {
        return answers;
    }

    let mut off = 12usize;
    let mut qname = String::new();
    for q in 0..qdcount {
        let Some((name, next)) = read_dns_name(msg, off, 0) else {
            return answers;
        };
        if next + 4 > msg.len() {
            return answers;
        }
        if q == 0 {
            qname = name;
        }
        off = next + 4;
    }
    if qname.is_empty() {
        return answers;
    }

    for _ in 0..ancount {
        let Some((aname, next)) = read_dns_name(msg, off, 0) else {
            return answers;
        };
        if next + 10 > msg.len() {
            return answers;
        }
        let typ = u16::from_be_bytes([msg[next], msg[next + 1]]);
        let class = u16::from_be_bytes([msg[next + 2], msg[next + 3]]);
        let ttl = u32::from_be_bytes([msg[next + 4], msg[next + 5], msg[next + 6], msg[next + 7]]);
        let rdlen = u16::from_be_bytes([msg[next + 8], msg[next + 9]]) as usize;
        let rdata = next + 10;
        if rdata + rdlen > msg.len() {
            return answers;
        }

        if class == DNS_CLASS_IN {
            match typ {
                DNS_TYPE_A if rdlen == 4 => {
                    let ip: [u8; 4] = msg[rdata..rdata + 4].try_into().unwrap();
                    answers.push(DnsAnswer {
                        qname: qname.clone(),
                        aname,
                        resource: Ipv4Addr::from(ip).to_string(),
                        ttl: clamp_ttl(ttl),
                    });
                }
                DNS_TYPE_AAAA if rdlen == 16 => {
                    let ip: [u8; 16] = msg[rdata..rdata + 16].try_into().unwrap();
                    answers.push(DnsAnswer {
                        qname: qname.clone(),
                        aname,
                        resource: Ipv6Addr::from(ip).to_string(),
                        ttl: clamp_ttl(ttl),
                    });
                }
                _ => {}
            }
        }
        off = rdata + rdlen;
    }
    answers
}

/// Reads a possibly-compressed DNS name. Returns the name and the offset of
/// the byte following it. Compression pointers are followed to a depth of 8.
fn read_dns_name(msg: &[u8], mut off: usize, depth: u32) -> Option<(String, usize)> {
    if depth > 8 || off >= msg.len() {
        return None;
    }
    let mut labels: Vec<String> = Vec::new();
    loop {
        if off >= msg.len() {
            return None;
        }
        let l = msg[off] as usize;
        match l & 0xc0 {
            0xc0 => {
                if off + 1 >= msg.len() {
                    return None;
                }
                let ptr = ((l & 0x3f) << 8) | msg[off + 1] as usize;
                let (name, _) = read_dns_name(msg, ptr, depth + 1)?;
                if !name.is_empty() {
                    labels.extend(name.split('.').map(str::to_owned));
                }
                return Some((labels.join("."), off + 2));
            }
            0x00 => {
                if l == 0 {
                    return Some((labels.join("."), off + 1));
                }
                off += 1;
                if l > 63 || off + l > msg.len() {
                    return None;
                }
                labels.push(String::from_utf8_lossy(&msg[off..off + l]).into_owned());
                off += l;
            }
            _ => return None,
        }
    }
}

fn clamp_ttl(ttl: u32) -> i32 {
    ttl.min(i32::MAX as u32) as i32
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    struct CollectingSink(Mutex<Vec<(String, String, String, i32)>>);

    impl DnsSink for CollectingSink {
        fn record_dns(&self, qname: &str, aname: &str, resource: &str, ttl: i32) {
            self.0.lock().unwrap().push((
                qname.to_owned(),
                aname.to_owned(),
                resource.to_owned(),
                ttl,
            ));
        }
    }

    struct PanickingSink;

    impl DnsSink for PanickingSink {
        fn record_dns(&self, _: &str, _: &str, _: &str, _: i32) {
            panic!("recording failed");
        }
    }

    fn dns_message(parts: &[Vec<u8>]) -> Vec<u8> {
        let mut msg = vec![0u8; 12];
        msg[0..2].copy_from_slice(&0x1234u16.to_be_bytes());
        msg[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
        msg[4..6].copy_from_slice(&1u16.to_be_bytes());
        msg[6..8].copy_from_slice(&((parts.len() - 1) as u16).to_be_bytes());
        for part in parts {
            msg.extend_from_slice(part);
        }
        msg
    }

    fn dns_name(name: &str) -> Vec<u8> {
        let mut out = Vec::new();
        for label in name.split('.') {
            out.push(label.len() as u8);
            out.extend_from_slice(label.as_bytes());
        }
        out.push(0);
        out
    }

    fn dns_question(name: &str, typ: u16) -> Vec<u8> {
        let mut out = dns_name(name);
        out.extend_from_slice(&typ.to_be_bytes());
        out.extend_from_slice(&DNS_CLASS_IN.to_be_bytes());
        out
    }

    fn dns_answer_bytes(name: &str, typ: u16, ttl: u32, rdata: &[u8]) -> Vec<u8> {
        let mut out = dns_name(name);
        out.extend_from_slice(&typ.to_be_bytes());
        out.extend_from_slice(&DNS_CLASS_IN.to_be_bytes());
        out.extend_from_slice(&ttl.to_be_bytes());
        out.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
        out.extend_from_slice(rdata);
        out
    }

    fn ipv4_udp(payload: &[u8]) -> Vec<u8> {
        let udp_len = 8 + payload.len();
        let total = 20 + udp_len;
        let mut packet = vec![0u8; total];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&(total as u16).to_be_bytes());
        packet[8] = 64;
        packet[9] = IP_PROTO_UDP;
        packet[12..16].copy_from_slice(&[10, 64, 0, 1]);
        packet[16..20].copy_from_slice(&[10, 0, 0, 2]);
        packet[20..22].copy_from_slice(&53u16.to_be_bytes());
        packet[22..24].copy_from_slice(&12345u16.to_be_bytes());
        packet[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
        packet[28..].copy_from_slice(payload);
        packet
    }

    fn ipv6_udp_with_destination_options(payload: &[u8]) -> Vec<u8> {
        let udp_len = 8 + payload.len();
        let total_payload = 8 + udp_len;
        let mut packet = vec![0u8; 40 + total_payload];
        packet[0] = 0x60;
        packet[4..6].copy_from_slice(&(total_payload as u16).to_be_bytes());
        packet[6] = IP_PROTO_DST_OPTS;
        packet[7] = 64;
        packet[40] = IP_PROTO_UDP;
        packet[41] = 0;
        packet[48..50].copy_from_slice(&53u16.to_be_bytes());
        packet[50..52].copy_from_slice(&12345u16.to_be_bytes());
        packet[52..54].copy_from_slice(&(udp_len as u16).to_be_bytes());
        packet[56..].copy_from_slice(payload);
        packet
    }

    #[test]
    fn parse_dns_answers_records_a_and_aaaa() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
            dns_answer_bytes(
                "tracker.example",
                DNS_TYPE_AAAA,
                60,
                &[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
            ),
        ]);

        let answers = parse_dns_answers(&msg);
        assert_eq!(answers.len(), 2);
        assert_eq!(answers[0].qname, "tracker.example");
        assert_eq!(answers[0].aname, "tracker.example");
        assert_eq!(answers[0].resource, "203.0.113.7");
        assert_eq!(answers[0].ttl, 300);
        assert_eq!(answers[1].resource, "2001:db8::1");
        assert_eq!(answers[1].ttl, 60);
    }

    #[test]
    fn parse_dns_answers_ignores_queries() {
        let mut msg = dns_message(&[dns_question("tracker.example", DNS_TYPE_A)]);
        msg[2] = 0x01;
        msg[3] = 0x00;
        assert!(parse_dns_answers(&msg).is_empty());
    }

    #[test]
    fn parse_dns_answers_follows_compression_pointers() {
        // Question at offset 12; answer name is a pointer back to it.
        let mut msg = dns_message(&[dns_question("tracker.example", DNS_TYPE_A)]);
        msg[6..8].copy_from_slice(&1u16.to_be_bytes()); // ancount = 1
        msg.extend_from_slice(&[0xc0, 12]); // pointer to qname
        msg.extend_from_slice(&DNS_TYPE_A.to_be_bytes());
        msg.extend_from_slice(&DNS_CLASS_IN.to_be_bytes());
        msg.extend_from_slice(&300u32.to_be_bytes());
        msg.extend_from_slice(&4u16.to_be_bytes());
        msg.extend_from_slice(&[203, 0, 113, 7]);

        let answers = parse_dns_answers(&msg);
        assert_eq!(answers.len(), 1);
        assert_eq!(answers[0].aname, "tracker.example");
        assert_eq!(answers[0].resource, "203.0.113.7");
    }

    #[test]
    fn udp_payload_uses_udp_length() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let mut packet = ipv4_udp(&msg);
        packet.extend_from_slice(&[0xaa, 0xbb, 0xcc]);

        let payload = udp_payload_from_dns_response(&packet).expect("packet not recognized");
        assert_eq!(payload.len(), msg.len());
    }

    #[test]
    fn udp_payload_walks_ipv6_extension_header() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let packet = ipv6_udp_with_destination_options(&msg);

        let payload = udp_payload_from_dns_response(&packet).expect("packet not recognized");
        assert_eq!(payload.len(), msg.len());
    }

    #[test]
    fn ipv6_fragment_is_rejected() {
        let msg = dns_message(&[dns_question("tracker.example", DNS_TYPE_A)]);
        let mut packet = ipv6_udp_with_destination_options(&msg);
        packet[6] = IP_PROTO_FRAGMENT;
        assert!(udp_payload_from_dns_response(&packet).is_none());
    }

    #[test]
    fn recorder_panic_does_not_propagate() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        inspect_dns_response(&ipv4_udp(&msg), &PanickingSink);
    }

    #[test]
    fn inspect_records_through_sink() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        inspect_dns_response(&ipv4_udp(&msg), &sink);
        let recorded = sink.0.lock().unwrap();
        assert_eq!(
            recorded.as_slice(),
            &[(
                "tracker.example".to_owned(),
                "tracker.example".to_owned(),
                "203.0.113.7".to_owned(),
                300
            )]
        );
    }
}

//! Passive DNS inspection of decrypted inbound packets (port of the old Go
//! wgbridge/dns.go). Extracts A/AAAA answers from UDP:53 responses and hands
//! them to the [`DnsSink`]; packets are never modified or blocked here.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::{Duration, Instant};

use crate::callbacks::DnsSink;

const DNS_TYPE_A: u16 = 1;
const DNS_TYPE_AAAA: u16 = 28;
const DNS_CLASS_IN: u16 = 1;

const IP_PROTO_HOP_BY_HOP: u8 = 0;
const IP_PROTO_TCP: u8 = 6;
const IP_PROTO_UDP: u8 = 17;
const IP_PROTO_ROUTING: u8 = 43;
const IP_PROTO_FRAGMENT: u8 = 44;
const IP_PROTO_DST_OPTS: u8 = 60;

const MAX_TCP_DNS_FLOWS: usize = 64;
const MAX_TCP_DNS_BUFFER: usize = u16::MAX as usize + 2;
const TCP_DNS_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

#[derive(Clone, Debug, Hash, PartialEq, Eq)]
struct TcpFlowKey {
    src: IpAddr,
    dst: IpAddr,
    src_port: u16,
    dst_port: u16,
}

struct TcpDnsFlow {
    next_seq: u32,
    buffer: Vec<u8>,
    last_seen: Instant,
}

/// Stateful passive DNS inspector. UDP messages are handled directly; TCP
/// messages are reassembled per flow using sequence numbers and the DNS-over-
/// TCP two-byte length prefix.
#[derive(Default)]
pub struct DnsInspector {
    tcp_flows: HashMap<TcpFlowKey, TcpDnsFlow>,
}

impl DnsInspector {
    pub fn inspect(&mut self, packet: &[u8], recorder: &dyn DnsSink) {
        if packet.is_empty() {
            return;
        }
        let Some((proto, segment)) = transport_segment(packet) else {
            return;
        };
        match proto {
            IP_PROTO_UDP => {
                if let Some(msg) = udp_dns_payload(segment) {
                    record_answers(msg, recorder);
                }
            }
            IP_PROTO_TCP => self.inspect_tcp(packet, segment, recorder),
            _ => {}
        }
    }

    fn inspect_tcp(&mut self, packet: &[u8], tcp: &[u8], recorder: &dyn DnsSink) {
        let Some(segment) = tcp_segment(packet, tcp) else {
            return;
        };
        let now = Instant::now();
        self.tcp_flows
            .retain(|_, flow| now.duration_since(flow.last_seen) <= TCP_DNS_IDLE_TIMEOUT);

        if segment.rst {
            self.tcp_flows.remove(&segment.key);
            return;
        }
        if segment.syn {
            if !self.tcp_flows.contains_key(&segment.key) {
                self.ensure_capacity();
            }
            self.tcp_flows.insert(
                segment.key.clone(),
                TcpDnsFlow {
                    next_seq: segment.seq.wrapping_add(1),
                    buffer: Vec::new(),
                    last_seen: now,
                },
            );
        }

        if !segment.payload.is_empty() {
            if !self.tcp_flows.contains_key(&segment.key) {
                self.ensure_capacity();
            }
            let data_seq = segment.seq.wrapping_add(u32::from(segment.syn));
            let flow = self
                .tcp_flows
                .entry(segment.key.clone())
                .or_insert_with(|| TcpDnsFlow {
                    // Best-effort bootstrap for a connection that pre-dates the
                    // inspector. A later gap invalidates the stream until a SYN.
                    next_seq: data_seq,
                    buffer: Vec::new(),
                    last_seen: now,
                });
            flow.last_seen = now;

            let already_seen = flow.next_seq.wrapping_sub(data_seq);
            let payload = if data_seq == flow.next_seq {
                segment.payload
            } else if already_seen < 0x8000_0000 && already_seen as usize <= segment.payload.len() {
                &segment.payload[already_seen as usize..]
            } else {
                // A forward gap means bytes needed to find message boundaries
                // are missing. Drop the flow instead of parsing a mid-message
                // payload as a new length prefix.
                self.tcp_flows.remove(&segment.key);
                return;
            };

            if flow.buffer.len() + payload.len() > MAX_TCP_DNS_BUFFER {
                self.tcp_flows.remove(&segment.key);
                return;
            }
            flow.buffer.extend_from_slice(payload);
            flow.next_seq = flow.next_seq.wrapping_add(payload.len() as u32);

            while flow.buffer.len() >= 2 {
                let msg_len = u16::from_be_bytes([flow.buffer[0], flow.buffer[1]]) as usize;
                if msg_len == 0 {
                    flow.buffer.drain(..2);
                    continue;
                }
                if flow.buffer.len() < msg_len + 2 {
                    break;
                }
                let msg = flow.buffer[2..2 + msg_len].to_vec();
                flow.buffer.drain(..2 + msg_len);
                record_answers(&msg, recorder);
            }
        }

        if segment.fin {
            self.tcp_flows.remove(&segment.key);
        }
    }

    fn ensure_capacity(&mut self) {
        if self.tcp_flows.len() < MAX_TCP_DNS_FLOWS {
            return;
        }
        if let Some(oldest) = self
            .tcp_flows
            .iter()
            .min_by_key(|(_, flow)| flow.last_seen)
            .map(|(key, _)| key.clone())
        {
            self.tcp_flows.remove(&oldest);
        }
    }
}

struct TcpSegment<'a> {
    key: TcpFlowKey,
    seq: u32,
    syn: bool,
    fin: bool,
    rst: bool,
    payload: &'a [u8],
}

pub fn inspect_dns_response(packet: &[u8], recorder: &dyn DnsSink) {
    DnsInspector::default().inspect(packet, recorder);
}

fn record_answers(msg: &[u8], recorder: &dyn DnsSink) {
    for rr in parse_dns_answers(msg) {
        // The recorder crosses into Java; never let a failure there take
        // down the packet path.
        let _ = catch_unwind(AssertUnwindSafe(|| {
            recorder.record_dns(&rr.qname, &rr.aname, &rr.resource, rr.ttl);
        }));
    }
}

fn tcp_segment<'a>(packet: &[u8], tcp: &'a [u8]) -> Option<TcpSegment<'a>> {
    if tcp.len() < 20 || u16::from_be_bytes([tcp[0], tcp[1]]) != 53 {
        return None;
    }
    let (src, dst) = match packet[0] >> 4 {
        4 => {
            // Reject IPv4 fragments: TCP sequence framing alone cannot fill
            // gaps created below the transport layer.
            let fragment = u16::from_be_bytes([packet[6], packet[7]]);
            if fragment & 0x3fff != 0 {
                return None;
            }
            (
                IpAddr::V4(Ipv4Addr::new(
                    packet[12], packet[13], packet[14], packet[15],
                )),
                IpAddr::V4(Ipv4Addr::new(
                    packet[16], packet[17], packet[18], packet[19],
                )),
            )
        }
        6 => {
            let src: [u8; 16] = packet[8..24].try_into().ok()?;
            let dst: [u8; 16] = packet[24..40].try_into().ok()?;
            (
                IpAddr::V6(Ipv6Addr::from(src)),
                IpAddr::V6(Ipv6Addr::from(dst)),
            )
        }
        _ => return None,
    };
    let data_off = ((tcp[12] >> 4) as usize) * 4;
    if data_off < 20 || tcp.len() < data_off {
        return None;
    }
    let flags = tcp[13];
    Some(TcpSegment {
        key: TcpFlowKey {
            src,
            dst,
            src_port: 53,
            dst_port: u16::from_be_bytes([tcp[2], tcp[3]]),
        },
        seq: u32::from_be_bytes(tcp[4..8].try_into().ok()?),
        syn: flags & 0x02 != 0,
        fin: flags & 0x01 != 0,
        rst: flags & 0x04 != 0,
        payload: &tcp[data_off..],
    })
}

/// Returns the (transport protocol, transport segment) for an IPv4/IPv6 packet,
/// walking IPv6 extension headers. Only UDP and TCP are reported; per-protocol
/// header validation is left to the payload extractors.
fn transport_segment(packet: &[u8]) -> Option<(u8, &[u8])> {
    match packet[0] >> 4 {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = ((packet[0] & 0x0f) as usize) * 4;
            if ihl < 20 || packet.len() < ihl {
                return None;
            }
            let proto = packet[9];
            if proto != IP_PROTO_UDP && proto != IP_PROTO_TCP {
                return None;
            }
            let mut total = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            if total == 0 || total > packet.len() {
                total = packet.len();
            }
            if total < ihl {
                return None;
            }
            Some((proto, &packet[ihl..total]))
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
                if next == IP_PROTO_UDP || next == IP_PROTO_TCP {
                    if total < off {
                        return None;
                    }
                    return Some((next, &packet[off..total]));
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

#[cfg(test)]
fn tcp_dns_payload(tcp: &[u8]) -> Option<&[u8]> {
    if tcp.len() < 20 {
        return None;
    }
    // Source port 53: this is a response from the resolver.
    if u16::from_be_bytes([tcp[0], tcp[1]]) != 53 {
        return None;
    }
    let data_off = ((tcp[12] >> 4) as usize) * 4;
    if data_off < 20 || tcp.len() < data_off + 2 {
        return None;
    }
    let msg = &tcp[data_off..];
    let msg_len = u16::from_be_bytes([msg[0], msg[1]]) as usize;
    // Only parse when the whole framed message is present in this segment.
    if msg_len == 0 || msg.len() < 2 + msg_len {
        return None;
    }
    Some(&msg[2..2 + msg_len])
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

    fn ipv4_tcp(payload: &[u8]) -> Vec<u8> {
        ipv4_tcp_segment(payload, 1000, 0x18)
    }

    fn ipv4_tcp_segment(payload: &[u8], seq: u32, flags: u8) -> Vec<u8> {
        // payload is the TCP data (already framed with the 2-byte DNS length).
        let tcp_hdr = 20;
        let seg_len = tcp_hdr + payload.len();
        let total = 20 + seg_len;
        let mut packet = vec![0u8; total];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&(total as u16).to_be_bytes());
        packet[8] = 64;
        packet[9] = IP_PROTO_TCP;
        packet[12..16].copy_from_slice(&[10, 64, 0, 1]);
        packet[16..20].copy_from_slice(&[10, 0, 0, 2]);
        packet[20..22].copy_from_slice(&53u16.to_be_bytes()); // src port
        packet[22..24].copy_from_slice(&12345u16.to_be_bytes()); // dst port
        packet[24..28].copy_from_slice(&seq.to_be_bytes());
        packet[32] = 5 << 4; // data offset = 5 words (20-byte header)
        packet[33] = flags;
        packet[40..].copy_from_slice(payload);
        packet
    }

    fn tcp_dns_framed(msg: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(2 + msg.len());
        out.extend_from_slice(&(msg.len() as u16).to_be_bytes());
        out.extend_from_slice(msg);
        out
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

        let (_, segment) = transport_segment(&packet).expect("packet not recognized");
        let payload = udp_dns_payload(segment).expect("UDP payload not recognized");
        assert_eq!(payload.len(), msg.len());
    }

    #[test]
    fn udp_payload_walks_ipv6_extension_header() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let packet = ipv6_udp_with_destination_options(&msg);

        let (_, segment) = transport_segment(&packet).expect("packet not recognized");
        let payload = udp_dns_payload(segment).expect("UDP payload not recognized");
        assert_eq!(payload.len(), msg.len());
    }

    #[test]
    fn ipv6_fragment_is_rejected() {
        let msg = dns_message(&[dns_question("tracker.example", DNS_TYPE_A)]);
        let mut packet = ipv6_udp_with_destination_options(&msg);
        packet[6] = IP_PROTO_FRAGMENT;
        assert!(transport_segment(&packet).is_none());
    }

    #[test]
    fn parse_tcp_dns_response() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let packet = ipv4_tcp(&tcp_dns_framed(&msg));

        let (_, segment) = transport_segment(&packet).expect("tcp packet not recognized");
        let payload = tcp_dns_payload(segment).expect("TCP payload not recognized");
        assert_eq!(payload, msg.as_slice());

        let answers = parse_dns_answers(payload);
        assert_eq!(answers.len(), 1);
        assert_eq!(answers[0].qname, "tracker.example");
        assert_eq!(answers[0].resource, "203.0.113.7");
    }

    #[test]
    fn stateful_inspector_reassembles_split_tcp_dns_response() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let second = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&first, &sink);
        assert!(sink.0.lock().unwrap().is_empty());
        inspector.inspect(&second, &sink);

        let records = sink.0.lock().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].0, "tracker.example");
        assert_eq!(records[0].2, "203.0.113.7");
    }

    #[test]
    fn stateful_inspector_ignores_retransmitted_tcp_bytes() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let packet = ipv4_tcp_segment(&framed, 1000, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&packet, &sink);
        inspector.inspect(&packet, &sink);

        assert_eq!(sink.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn stateful_inspector_discards_stream_after_sequence_gap() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let after_gap = ipv4_tcp_segment(&framed[split..], 1001 + split as u32, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&first, &sink);
        inspector.inspect(&after_gap, &sink);

        assert!(sink.0.lock().unwrap().is_empty());
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

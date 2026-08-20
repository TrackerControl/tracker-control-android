//! DNS inspection and response policy for decrypted inbound packets (port of
//! the old Go wgbridge/dns.go). Extracts A/AAAA answers from UDP:53 responses,
//! hands them to the [`DnsSink`], and applies the native SVCB/HTTPS and
//! explicit-domain response policy before packets reach the TUN.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::ops::Range;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::{Duration, Instant};

use crate::callbacks::DnsSink;

const DNS_TYPE_A: u16 = 1;
const DNS_TYPE_AAAA: u16 = 28;
const DNS_CLASS_IN: u16 = 1;
const DNS_TYPE_SVCB: u16 = 64;
const DNS_TYPE_HTTPS: u16 = 65;
const DNS_HEADER_LEN: usize = 12;

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
    framing_known: bool,
}

struct TcpRewriteContext {
    /// Complete DNS message ranges within the current TCP segment. The
    /// two-byte DNS-over-TCP length prefix is outside each range.
    frames: Vec<Range<usize>>,
}

/// Stateful DNS inspector. UDP messages are handled directly; TCP messages
/// are reassembled per flow using sequence numbers and the DNS-over-TCP
/// two-byte length prefix. The same framing state gates TCP response rewrite.
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
            IP_PROTO_TCP => {
                let _ = self.inspect_tcp(packet, segment, recorder);
            }
            _ => {}
        }
    }

    fn inspect_tcp(
        &mut self,
        packet: &[u8],
        tcp: &[u8],
        recorder: &dyn DnsSink,
    ) -> Option<TcpRewriteContext> {
        let Some(segment) = tcp_segment(packet, tcp) else {
            return None;
        };
        let mut context = TcpRewriteContext { frames: Vec::new() };
        let now = Instant::now();
        self.tcp_flows
            .retain(|_, flow| now.duration_since(flow.last_seen) <= TCP_DNS_IDLE_TIMEOUT);

        if segment.rst {
            self.tcp_flows.remove(&segment.key);
            return Some(context);
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
                    framing_known: true,
                },
            );
        }

        if !segment.payload.is_empty() {
            if !self.tcp_flows.contains_key(&segment.key) {
                self.ensure_capacity();
            }
            let data_seq = segment.seq.wrapping_add(u32::from(segment.syn));
            let next_seq = if let Some(next_seq) =
                self.tcp_flows.get(&segment.key).map(|flow| flow.next_seq)
            {
                next_seq
            } else {
                self.tcp_flows.insert(
                    segment.key.clone(),
                    TcpDnsFlow {
                        // Best-effort bootstrap for a connection that pre-dates the
                        // inspector. It is intentionally not rewrite-eligible until
                        // a SYN establishes a known DNS frame boundary.
                        next_seq: data_seq,
                        buffer: Vec::new(),
                        last_seen: now,
                        framing_known: false,
                    },
                );
                data_seq
            };

            let already_seen = next_seq.wrapping_sub(data_seq);
            let payload_start = if data_seq == next_seq {
                0
            } else if already_seen >= 0x8000_0000 {
                // A forward gap means bytes needed to find message boundaries
                // are missing. Drop the flow instead of parsing a mid-message
                // payload as a new length prefix.
                self.tcp_flows.remove(&segment.key);
                return Some(context);
            } else {
                // A wholly-old or partially-overlapping retransmission: skip
                // the bytes we've already consumed and keep the flow alive.
                // If the retransmission lies entirely behind the frontier,
                // this yields an empty tail, which is a harmless no-op.
                (already_seen as usize).min(segment.payload.len())
            };

            let payload = &segment.payload[payload_start..];
            let flow = self.tcp_flows.get_mut(&segment.key).expect("flow inserted");
            flow.last_seen = now;
            if flow.buffer.len() + payload.len() > MAX_TCP_DNS_BUFFER {
                self.tcp_flows.remove(&segment.key);
                return Some(context);
            }
            if flow.framing_known {
                for range in complete_frame_ranges(&flow.buffer, payload) {
                    context.frames.push(
                        (segment.payload_offset + payload_start + range.start)
                            ..(segment.payload_offset + payload_start + range.end),
                    );
                }
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
        Some(context)
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
    payload_offset: usize,
    payload: &'a [u8],
}

/// Finds complete DNS frames whose prefix starts in `incoming` after any
/// previously buffered frame has been consumed. Returning only those ranges
/// prevents a continuation or retransmission from being parsed as a fresh
/// DNS-over-TCP message.
fn complete_frame_ranges(buffer: &[u8], incoming: &[u8]) -> Vec<Range<usize>> {
    let mut combined = Vec::with_capacity(buffer.len() + incoming.len());
    combined.extend_from_slice(buffer);
    combined.extend_from_slice(incoming);
    let prefix_len = buffer.len();
    let mut cursor = 0usize;

    // Consume the frame that began in the previous segment. It may complete
    // in `incoming`; until it does, no byte in this segment is frame-aligned.
    while cursor < prefix_len {
        if cursor + 2 > combined.len() {
            return Vec::new();
        }
        let msg_len = u16::from_be_bytes([combined[cursor], combined[cursor + 1]]) as usize;
        let frame_len = if msg_len == 0 { 2 } else { 2 + msg_len };
        if cursor + frame_len > combined.len() {
            return Vec::new();
        }
        cursor += frame_len;
    }

    let mut ranges = Vec::new();
    while cursor + 2 <= combined.len() {
        let msg_len = u16::from_be_bytes([combined[cursor], combined[cursor + 1]]) as usize;
        if msg_len == 0 {
            cursor += 2;
            continue;
        }
        let frame_end = cursor + 2 + msg_len;
        if frame_end > combined.len() {
            break;
        }
        if cursor >= prefix_len {
            ranges.push((cursor - prefix_len + 2)..(frame_end - prefix_len));
        }
        cursor = frame_end;
    }
    ranges
}

pub fn inspect_dns_response(packet: &[u8], recorder: &dyn DnsSink) {
    DnsInspector::default().inspect(packet, recorder);
}

impl DnsInspector {
    /// Inspects a decrypted packet and applies DNS policy before it reaches
    /// the TUN. TCP rewriting is deliberately coupled to this inspector's
    /// sequence/framing state; callers must not use the stateless UDP helper
    /// for TCP segments.
    pub fn inspect_and_rewrite(
        &mut self,
        packet: &mut [u8],
        policy: &dyn DnsSink,
    ) -> Option<usize> {
        let Some(view) = dns_packet_view(packet) else {
            self.inspect(packet, policy);
            return None;
        };
        if view.is_udp {
            self.inspect(packet, policy);
            return rewrite_dns_response(packet, policy);
        }

        let tcp = &packet[view.transport_offset..view.ip_total_len];
        let Some(context) = self.inspect_tcp(packet, tcp, policy) else {
            return None;
        };
        let mut rewritten = false;
        for range in context.frames {
            let msg_start = view.transport_offset + range.start;
            let msg_end = view.transport_offset + range.end;
            if msg_end > packet.len() {
                continue;
            }
            let msg = &packet[msg_start..msg_end];
            let Some(layout) = parse_dns_layout(msg) else {
                continue;
            };
            if layout.contains_svcb || policy_is_domain_blocked(policy, &layout.qname) {
                blank_dns_message(
                    &mut packet[msg_start..msg_end],
                    policy_blocked_rcode(policy),
                );
                rewritten = true;
            }
        }
        if !rewritten {
            return None;
        }

        repair_packet(packet, &view, view.ip_total_len);
        Some(view.ip_total_len)
    }
}

/// Applies the native DNS response policy to one decrypted IP packet.
///
/// The caller must run [`DnsInspector::inspect`] first. That preserves the
/// native ordering where A/AAAA answers are recorded before a response is
/// blanked. UDP responses can be shortened because they are datagrams. TCP
/// packets are never shortened: changing a DNS-over-TCP payload length would
/// require sequence-number translation for every later segment. Instead, a
/// complete UDP response has its counts cleared and is trimmed to the question
/// section. TCP must go through [`DnsInspector::inspect_and_rewrite`], which
/// aligns complete frames to the tracked TCP sequence frontier.
///
/// Returns the packet length to write when a response was rewritten. `None`
/// means that the packet was not a DNS response or policy left it unchanged.
pub fn rewrite_dns_response(packet: &mut [u8], policy: &dyn DnsSink) -> Option<usize> {
    let view = dns_packet_view(packet)?;
    if !view.is_udp {
        return None;
    }

    let msg = &packet[view.dns_start..view.dns_end];
    let layout = parse_dns_layout(msg)?;
    if !layout.contains_svcb && !policy_is_domain_blocked(policy, &layout.qname) {
        return None;
    }

    let msg = &mut packet[view.dns_start..view.dns_end];
    blank_dns_message(msg, policy_blocked_rcode(policy));
    let new_total = view.dns_start + layout.question_end;
    repair_packet(packet, &view, new_total);
    Some(new_total)
}

#[derive(Clone, Copy, Debug)]
struct DnsPacketView {
    ip_version: u8,
    ip_header_len: usize,
    transport_offset: usize,
    dns_start: usize,
    dns_end: usize,
    ip_total_len: usize,
    is_udp: bool,
}

fn dns_packet_view(packet: &[u8]) -> Option<DnsPacketView> {
    if packet.is_empty() {
        return None;
    }
    match packet[0] >> 4 {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || ihl > packet.len() {
                return None;
            }
            let total = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            let total = if total == 0 { packet.len() } else { total };
            if total < ihl || total > packet.len() {
                return None;
            }
            // A non-zero fragment offset has no complete transport header.
            // The first fragment is also left alone when MF is set: a DNS
            // response cannot be safely rewritten without the full datagram.
            let fragment = u16::from_be_bytes([packet[6], packet[7]]);
            if fragment & 0x3fff != 0 || fragment & 0x2000 != 0 {
                return None;
            }
            let proto = packet[9];
            let (is_udp, header_len) = match proto {
                IP_PROTO_UDP => (true, 8),
                IP_PROTO_TCP => {
                    if total < ihl + 20 {
                        return None;
                    }
                    let tcp = ihl;
                    let data_offset = (packet[tcp + 12] >> 4) as usize * 4;
                    if data_offset < 20 || total < tcp + data_offset {
                        return None;
                    }
                    (false, data_offset)
                }
                _ => return None,
            };
            let transport = ihl;
            if total < transport + header_len
                || u16::from_be_bytes([packet[transport], packet[transport + 1]]) != 53
            {
                return None;
            }
            let dns_start = transport + header_len;
            let dns_end = if is_udp {
                let udp_len =
                    u16::from_be_bytes([packet[transport + 4], packet[transport + 5]]) as usize;
                if udp_len < 8 || transport + udp_len > total {
                    return None;
                }
                transport + udp_len
            } else {
                total
            };
            if dns_end <= dns_start {
                return None;
            }
            Some(DnsPacketView {
                ip_version: 4,
                ip_header_len: ihl,
                transport_offset: transport,
                dns_start,
                dns_end,
                ip_total_len: total,
                is_udp,
            })
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let payload_len = u16::from_be_bytes([packet[4], packet[5]]) as usize;
            let total = if payload_len == 0 {
                packet.len()
            } else {
                40 + payload_len
            };
            if total < 40 || total > packet.len() {
                return None;
            }
            let mut next = packet[6];
            let mut transport = 40usize;
            while next != IP_PROTO_UDP && next != IP_PROTO_TCP {
                if next == IP_PROTO_FRAGMENT || !is_ipv6_ext_header(next) || total < transport + 2 {
                    return None;
                }
                let ext_len = (packet[transport + 1] as usize + 1) * 8;
                if ext_len < 8 || total < transport + ext_len {
                    return None;
                }
                next = packet[transport];
                transport += ext_len;
            }
            let (is_udp, header_len) = if next == IP_PROTO_UDP {
                (true, 8)
            } else {
                if total < transport + 20 {
                    return None;
                }
                let data_offset = (packet[transport + 12] >> 4) as usize * 4;
                if data_offset < 20 || total < transport + data_offset {
                    return None;
                }
                (false, data_offset)
            };
            if total < transport + header_len
                || u16::from_be_bytes([packet[transport], packet[transport + 1]]) != 53
            {
                return None;
            }
            let dns_start = transport + header_len;
            let dns_end = if is_udp {
                let udp_len =
                    u16::from_be_bytes([packet[transport + 4], packet[transport + 5]]) as usize;
                if udp_len < 8 || transport + udp_len > total {
                    return None;
                }
                transport + udp_len
            } else {
                total
            };
            if dns_end <= dns_start {
                return None;
            }
            Some(DnsPacketView {
                ip_version: 6,
                ip_header_len: 40,
                transport_offset: transport,
                dns_start,
                dns_end,
                ip_total_len: total,
                is_udp,
            })
        }
        _ => None,
    }
}

#[derive(Debug)]
struct DnsLayout {
    qname: String,
    question_end: usize,
    contains_svcb: bool,
}

fn parse_dns_layout(msg: &[u8]) -> Option<DnsLayout> {
    if msg.len() < DNS_HEADER_LEN {
        return None;
    }
    let flags = u16::from_be_bytes([msg[2], msg[3]]);
    if flags & 0x8000 == 0 || flags & 0x7800 != 0 {
        return None;
    }
    let qdcount = u16::from_be_bytes([msg[4], msg[5]]) as usize;
    let ancount = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    if qdcount == 0 || ancount == 0 {
        return None;
    }

    let mut off = DNS_HEADER_LEN;
    let mut qname = None;
    for q in 0..qdcount {
        let (name, next) = read_dns_name(msg, off, 0)?;
        if next + 4 > msg.len() {
            return None;
        }
        if q == 0 {
            qname = Some(name);
        }
        off = next + 4;
    }
    let question_end = off;
    let mut contains_svcb = false;
    for _ in 0..ancount {
        let (_name, next) = read_dns_name(msg, off, 0)?;
        if next + 10 > msg.len() {
            return None;
        }
        let typ = u16::from_be_bytes([msg[next], msg[next + 1]]);
        let class = u16::from_be_bytes([msg[next + 2], msg[next + 3]]);
        let rdlen = u16::from_be_bytes([msg[next + 8], msg[next + 9]]) as usize;
        let rdata = next + 10;
        if rdata + rdlen > msg.len() {
            return None;
        }
        contains_svcb |= class == DNS_CLASS_IN && (typ == DNS_TYPE_SVCB || typ == DNS_TYPE_HTTPS);
        off = rdata + rdlen;
    }
    Some(DnsLayout {
        qname: qname?,
        question_end,
        contains_svcb,
    })
}

fn blank_dns_message(msg: &mut [u8], rcode: u8) {
    // Keep the ID and question section. The trailing answer bytes are left in
    // place for TCP sequence safety; counts make them unreachable to DNS
    // parsers. UDP callers trim the datagram at question_end afterwards.
    let flags = 0x8000u16 | u16::from(rcode & 0x0f);
    msg[2..4].copy_from_slice(&flags.to_be_bytes());
    msg[6..12].fill(0);
}

fn policy_is_domain_blocked(policy: &dyn DnsSink, qname: &str) -> bool {
    catch_unwind(AssertUnwindSafe(|| policy.is_domain_blocked(qname))).unwrap_or(false)
}

fn policy_blocked_rcode(policy: &dyn DnsSink) -> u8 {
    catch_unwind(AssertUnwindSafe(|| policy.blocked_rcode()))
        .unwrap_or(3)
        .min(15)
}

fn repair_packet(packet: &mut [u8], view: &DnsPacketView, new_total: usize) {
    let transport_len = new_total.saturating_sub(view.transport_offset);
    if view.is_udp {
        packet[view.transport_offset + 4..view.transport_offset + 6]
            .copy_from_slice(&(transport_len as u16).to_be_bytes());
    }
    if view.ip_version == 4 {
        packet[2..4].copy_from_slice(&(new_total as u16).to_be_bytes());
    } else {
        packet[4..6].copy_from_slice(&((new_total - view.ip_header_len) as u16).to_be_bytes());
    }

    let checksum_offset = if view.is_udp {
        view.transport_offset + 6
    } else {
        view.transport_offset + 16
    };
    let old_checksum = u16::from_be_bytes([packet[checksum_offset], packet[checksum_offset + 1]]);
    if !(view.is_udp && view.ip_version == 4 && old_checksum == 0) {
        packet[checksum_offset..checksum_offset + 2].fill(0);
        let checksum =
            encode_transport_checksum(transport_checksum(packet, view, new_total), view.is_udp);
        packet[checksum_offset..checksum_offset + 2].copy_from_slice(&checksum.to_be_bytes());
    }
    if view.ip_version == 4 {
        packet[10..12].fill(0);
        let checksum = internet_checksum(&packet[..view.ip_header_len]);
        packet[10..12].copy_from_slice(&checksum.to_be_bytes());
    }
}

/// Encodes a computed transport checksum for the wire.
///
/// Only UDP maps a computed zero to `0xffff` (RFC 768: zero means "no
/// checksum"). TCP has no such convention, so a zero checksum is a valid
/// value there and rewriting it would corrupt the segment.
fn encode_transport_checksum(checksum: u16, is_udp: bool) -> u16 {
    if is_udp && checksum == 0 {
        u16::MAX
    } else {
        checksum
    }
}

fn transport_checksum(packet: &[u8], view: &DnsPacketView, total: usize) -> u16 {
    let transport = &packet[view.transport_offset..total];
    let mut pseudo =
        Vec::with_capacity(if view.ip_version == 4 { 12 } else { 40 } + transport.len());
    if view.ip_version == 4 {
        pseudo.extend_from_slice(&packet[12..16]);
        pseudo.extend_from_slice(&packet[16..20]);
        pseudo.extend_from_slice(&[
            0,
            if view.is_udp {
                IP_PROTO_UDP
            } else {
                IP_PROTO_TCP
            },
        ]);
        pseudo.extend_from_slice(&(transport.len() as u16).to_be_bytes());
    } else {
        pseudo.extend_from_slice(&packet[8..24]);
        pseudo.extend_from_slice(&packet[24..40]);
        pseudo.extend_from_slice(&(transport.len() as u32).to_be_bytes());
        pseudo.extend_from_slice(&[
            0,
            0,
            0,
            if view.is_udp {
                IP_PROTO_UDP
            } else {
                IP_PROTO_TCP
            },
        ]);
    }
    pseudo.extend_from_slice(transport);
    internet_checksum(&pseudo)
}

fn internet_checksum(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut chunks = data.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    if let Some(&byte) = chunks.remainder().first() {
        sum += u32::from(byte) << 8;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
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
        payload_offset: data_off,
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
    fn stateful_inspector_ignores_wholly_old_retransmission() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        // Retransmission of a prefix that lies entirely behind the frontier
        // already advanced past by `first` (i.e. already_seen > payload.len()).
        let old_retransmit = ipv4_tcp_segment(&framed[..split / 4], 1000, 0x18);
        let second = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&first, &sink);
        assert!(sink.0.lock().unwrap().is_empty());
        inspector.inspect(&old_retransmit, &sink);
        assert!(sink.0.lock().unwrap().is_empty());
        inspector.inspect(&second, &sink);

        let records = sink.0.lock().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].0, "tracker.example");
        assert_eq!(records[0].2, "203.0.113.7");
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

    struct BlockingSink;

    impl DnsSink for BlockingSink {
        fn record_dns(&self, _: &str, _: &str, _: &str, _: i32) {}

        fn is_domain_blocked(&self, _: &str) -> bool {
            true
        }
    }

    fn svcb_response() -> Vec<u8> {
        dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
            dns_answer_bytes("tracker.example", DNS_TYPE_HTTPS, 300, &[]),
        ])
    }

    fn assert_transport_checksum_valid(packet: &[u8]) {
        let view = dns_packet_view(packet).expect("DNS packet view");
        assert_eq!(transport_checksum(packet, &view, packet.len()), 0);
    }

    #[test]
    fn zero_transport_checksum_is_encoded_as_ffff_for_udp_only() {
        assert_eq!(encode_transport_checksum(0, true), u16::MAX);
        assert_eq!(encode_transport_checksum(1, true), 1);
        // Zero is a valid TCP checksum; encoding it as 0xffff would make the
        // receiver drop every copy of the rewritten segment.
        assert_eq!(encode_transport_checksum(0, false), 0);
        assert_eq!(encode_transport_checksum(1, false), 1);
    }

    #[test]
    fn rewrite_svcb_ipv4_udp_trims_and_repairs_checksums() {
        let msg = svcb_response();
        let question_end = parse_dns_layout(&msg).unwrap().question_end;
        let mut packet = ipv4_udp(&msg);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        // The A record is recorded before the response is blanked.
        inspector.inspect(&packet, &sink);
        assert_eq!(sink.0.lock().unwrap().len(), 1);
        // A non-zero incoming checksum exercises the rewrite path. IPv4 UDP
        // packets with checksum zero deliberately retain zero.
        packet[26..28].copy_from_slice(&0x1234u16.to_be_bytes());
        let new_len = rewrite_dns_response(&mut packet, &sink).unwrap();

        assert_eq!(new_len, 20 + 8 + question_end);
        packet.truncate(new_len);
        assert_eq!(packet.len(), new_len);
        let (_, segment) = transport_segment(&packet).unwrap();
        let payload = udp_dns_payload(segment).unwrap();
        assert_eq!(payload.len(), question_end);
        assert_eq!(u16::from_be_bytes([payload[2], payload[3]]), 0x8003);
        assert_eq!(&payload[6..12], &[0, 0, 0, 0, 0, 0]);
        assert_eq!(
            u16::from_be_bytes([packet[2], packet[3]]) as usize,
            packet.len()
        );
        assert_eq!(internet_checksum(&packet[..20]), 0);
        assert_transport_checksum_valid(&packet);
    }

    #[test]
    fn rewrite_svcb_ipv6_udp_updates_payload_and_checksum() {
        let msg = svcb_response();
        let question_end = parse_dns_layout(&msg).unwrap().question_end;
        let mut packet = ipv6_udp_with_destination_options(&msg);
        let sink = CollectingSink(Mutex::new(Vec::new()));

        let new_len = rewrite_dns_response(&mut packet, &sink).unwrap();
        assert_eq!(new_len, 48 + 8 + question_end);
        packet.truncate(new_len);
        assert_eq!(
            u16::from_be_bytes([packet[4], packet[5]]) as usize,
            packet.len() - 40
        );
        let (_, segment) = transport_segment(&packet).unwrap();
        assert_eq!(udp_dns_payload(segment).unwrap().len(), question_end);
        assert_transport_checksum_valid(&packet);
    }

    #[test]
    fn rewrite_blocked_domain_ipv4_udp_uses_explicit_policy_hook() {
        let msg = dns_message(&[
            dns_question("blocked.example", DNS_TYPE_A),
            dns_answer_bytes("blocked.example", DNS_TYPE_A, 300, &[203, 0, 113, 8]),
        ]);
        let question_end = parse_dns_layout(&msg).unwrap().question_end;
        let mut packet = ipv4_udp(&msg);
        packet[26..28].copy_from_slice(&0x1234u16.to_be_bytes());

        let new_len = rewrite_dns_response(&mut packet, &BlockingSink).unwrap();
        assert_eq!(new_len, 20 + 8 + question_end);
        packet.truncate(new_len);
        let (_, segment) = transport_segment(&packet).unwrap();
        let payload = udp_dns_payload(segment).unwrap();
        assert_eq!(payload.len(), question_end);
        assert_eq!(u16::from_be_bytes([payload[2], payload[3]]), 0x8003);
        assert_transport_checksum_valid(&packet);
    }

    #[test]
    fn rewrite_tcp_keeps_frame_and_packet_lengths() {
        let msg = svcb_response();
        let framed = tcp_dns_framed(&msg);
        let mut packet = ipv4_tcp(&framed);
        let original_len = packet.len();
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        // A standalone data segment is not rewrite-eligible until a SYN has
        // established the TCP frame boundary.
        assert_eq!(inspector.inspect_and_rewrite(&mut packet, &sink), None);
        let syn = ipv4_tcp_segment(&[], 999, 0x12);
        let mut packet = syn;
        let _ = inspector.inspect_and_rewrite(&mut packet, &sink);
        let mut packet = ipv4_tcp_segment(&framed, 1000, 0x18);
        assert_eq!(
            inspector.inspect_and_rewrite(&mut packet, &sink),
            Some(original_len)
        );
        assert_eq!(packet.len(), original_len);
        let (_, segment) = transport_segment(&packet).unwrap();
        let payload = &segment[20..];
        let msg_len = u16::from_be_bytes([payload[0], payload[1]]) as usize;
        assert_eq!(msg_len, msg.len());
        assert_eq!(u16::from_be_bytes([payload[2 + 2], payload[2 + 3]]), 0x8003);
        assert_eq!(&payload[2 + 6..2 + 12], &[0, 0, 0, 0, 0, 0]);
        assert_transport_checksum_valid(&packet);
    }

    #[test]
    fn rewrite_tcp_split_frame_is_a_no_op() {
        let framed = tcp_dns_framed(&svcb_response());
        let split = framed.len() / 2;
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();
        let syn = ipv4_tcp_segment(&[], 999, 0x12);
        let mut syn_packet = syn;
        inspector.inspect_and_rewrite(&mut syn_packet, &sink);
        let mut packet = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let before = packet.clone();

        assert_eq!(inspector.inspect_and_rewrite(&mut packet, &sink), None);
        assert_eq!(packet, before);
    }

    #[test]
    fn rewrite_tcp_continuation_cannot_be_parsed_as_nested_frame() {
        let nested = tcp_dns_framed(&svcb_response());
        let outer_len = 200usize;
        let mut first_payload = Vec::with_capacity(2 + outer_len / 2);
        first_payload.extend_from_slice(&(outer_len as u16).to_be_bytes());
        first_payload.resize(2 + outer_len / 2, 0xaa);
        let first_data_len = first_payload.len() as u32;
        let second_payload = nested;
        // The nested-looking frame is actually still part of the outer DNS
        // message, which remains incomplete after this continuation.
        assert!(first_payload.len() + second_payload.len() < 2 + outer_len);

        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();
        let mut syn_packet = ipv4_tcp_segment(&[], 999, 0x12);
        inspector.inspect_and_rewrite(&mut syn_packet, &sink);
        let mut first = ipv4_tcp_segment(&first_payload, 1000, 0x18);
        inspector.inspect_and_rewrite(&mut first, &sink);
        let mut continuation = ipv4_tcp_segment(&second_payload, 1000 + first_data_len, 0x18);
        let before = continuation.clone();

        assert_eq!(
            inspector.inspect_and_rewrite(&mut continuation, &sink),
            None
        );
        assert_eq!(continuation, before);
    }

    #[test]
    fn rewrite_ordinary_a_response_is_a_no_op_with_default_policy() {
        let msg = dns_message(&[
            dns_question("ordinary.example", DNS_TYPE_A),
            dns_answer_bytes("ordinary.example", DNS_TYPE_A, 300, &[203, 0, 113, 9]),
        ]);
        let mut packet = ipv4_udp(&msg);
        let before = packet.clone();
        let sink = CollectingSink(Mutex::new(Vec::new()));

        assert_eq!(rewrite_dns_response(&mut packet, &sink), None);
        assert_eq!(packet, before);
    }

    #[test]
    fn rewrite_non_in_svcb_answer_is_a_no_op() {
        let mut msg = dns_message(&[
            dns_question("ordinary.example", DNS_TYPE_A),
            dns_answer_bytes("ordinary.example", DNS_TYPE_HTTPS, 300, &[]),
        ]);
        let layout = parse_dns_layout(&msg).unwrap();
        let (_, answer_name_end) = read_dns_name(&msg, layout.question_end, 0).unwrap();
        msg[answer_name_end + 2..answer_name_end + 4].copy_from_slice(&2u16.to_be_bytes());
        let mut packet = ipv4_udp(&msg);
        let before = packet.clone();
        let sink = CollectingSink(Mutex::new(Vec::new()));

        assert_eq!(rewrite_dns_response(&mut packet, &sink), None);
        assert_eq!(packet, before);
    }
}

//! DNS inspection and response policy for decrypted inbound packets (port of
//! the old Go wgbridge/dns.go). Extracts A/AAAA answers from UDP:53 responses,
//! hands them to the [`DnsSink`], and applies the native SVCB/HTTPS and
//! explicit-domain response policy before packets reach the TUN.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::ops::Range;
use std::time::{Duration, Instant};

use crate::callbacks::DnsSink;
use tcdns::{
    apply_policy, process_response, record_answers as record_dns_answers, DnsPolicy, Outcome,
};

const IP_PROTO_HOP_BY_HOP: u8 = 0;
const IP_PROTO_TCP: u8 = 6;
const IP_PROTO_UDP: u8 = 17;
const IP_PROTO_ROUTING: u8 = 43;
const IP_PROTO_FRAGMENT: u8 = 44;
const IP_PROTO_DST_OPTS: u8 = 60;

const MAX_TCP_DNS_FLOWS: usize = 64;
const MAX_TCP_DNS_BUFFER: usize = u16::MAX as usize + 2;
const MAX_TCP_DNS_PENDING_SEGMENTS: usize = 8;
const MAX_TCP_DNS_PENDING_BYTES: usize = 16 * 1024;
const TCP_DNS_GAP_TIMEOUT: Duration = Duration::from_secs(5);
const TCP_SEQ_HALF_RANGE: u32 = 0x8000_0000;

struct SinkPolicy<'a>(&'a dyn DnsSink);

impl DnsPolicy for SinkPolicy<'_> {
    fn record_answer(&self, qname: &str, aname: &str, resource: &str, ttl: i32) {
        self.0.record_dns(qname, aname, resource, ttl);
    }

    fn is_domain_blocked(&self, qname: &str) -> bool {
        self.0.is_domain_blocked(qname)
    }

    fn blocked_rcode(&self) -> u8 {
        self.0.blocked_rcode()
    }
}

#[derive(Clone, Debug, Hash, PartialEq, Eq)]
struct TcpFlowKey {
    src: IpAddr,
    dst: IpAddr,
    src_port: u16,
    dst_port: u16,
}

struct TcpDnsFlow {
    next_seq: u32,
    fin_seq: Option<u32>,
    buffer: Vec<u8>,
    last_seen: Instant,
    framing_known: bool,
    pending: Vec<TcpPendingSegment>,
    pending_bytes: usize,
}

struct TcpPendingSegment {
    seq: u32,
    payload: Vec<u8>,
    first_seen: Instant,
}

struct TcpRewriteContext {
    /// Complete DNS message ranges within the current TCP segment. The
    /// two-byte DNS-over-TCP length prefix is outside each range.
    frames: Vec<Range<usize>>,
}

struct ContiguousChunk {
    bytes: Vec<u8>,
    /// Source range in the TCP payload of the packet currently being handled.
    /// Bytes drained from an earlier out-of-order packet have no source range
    /// and are therefore never rewritten retroactively.
    current_range: Option<Range<usize>>,
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
                    record_dns_answers(msg, &SinkPolicy(recorder));
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
        let segment = tcp_segment(packet, tcp)?;
        let mut context = TcpRewriteContext { frames: Vec::new() };
        let now = Instant::now();
        self.expire_pending(now);

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
                    fin_seq: None,
                    buffer: Vec::new(),
                    last_seen: now,
                    framing_known: true,
                    pending: Vec::new(),
                    pending_bytes: 0,
                },
            );
        }

        if !segment.payload.is_empty() {
            if !self.tcp_flows.contains_key(&segment.key) {
                self.ensure_capacity();
            }
            let data_seq = segment.seq.wrapping_add(u32::from(segment.syn));
            if !self.tcp_flows.contains_key(&segment.key) {
                self.tcp_flows.insert(
                    segment.key.clone(),
                    TcpDnsFlow {
                        // Best-effort bootstrap for a connection that pre-dates the
                        // inspector. It is intentionally not rewrite-eligible until
                        // a SYN establishes a known DNS frame boundary.
                        next_seq: data_seq,
                        fin_seq: None,
                        buffer: Vec::new(),
                        last_seen: now,
                        framing_known: false,
                        pending: Vec::new(),
                        pending_bytes: 0,
                    },
                );
            }

            let flow = self.tcp_flows.get_mut(&segment.key).expect("flow inserted");
            flow.last_seen = now;
            if is_seq_after(data_seq, flow.next_seq) {
                queue_pending(flow, data_seq, segment.payload, now);
            } else {
                let payload_start = if data_seq == flow.next_seq {
                    0
                } else {
                    let already_seen = flow.next_seq.wrapping_sub(data_seq);
                    if already_seen >= TCP_SEQ_HALF_RANGE {
                        segment.payload.len()
                    } else {
                        (already_seen as usize).min(segment.payload.len())
                    }
                };
                let mut chunks = Vec::new();
                if payload_start < segment.payload.len() {
                    let payload = &segment.payload[payload_start..];
                    let base_seq = flow.next_seq;
                    let mut uncovered = vec![(0usize, payload.len())];
                    for pending in &flow.pending {
                        let Some(pending_start) = seq_forward_offset(base_seq, pending.seq) else {
                            continue;
                        };
                        let pending_end = pending_start.saturating_add(pending.payload.len());
                        let mut remaining = Vec::with_capacity(uncovered.len() + 1);
                        for (start, end) in uncovered {
                            if pending_end <= start || pending_start >= end {
                                remaining.push((start, end));
                                continue;
                            }
                            if start < pending_start {
                                remaining.push((start, pending_start));
                            }
                            if pending_end < end {
                                remaining.push((pending_end, end));
                            }
                        }
                        uncovered = remaining;
                    }
                    for (start, end) in uncovered {
                        drain_pending(flow, &mut chunks);
                        if flow.next_seq != base_seq.wrapping_add(start as u32)
                            || flow.buffer.len()
                                + chunks.iter().map(|chunk| chunk.bytes.len()).sum::<usize>()
                                + end
                                - start
                                > MAX_TCP_DNS_BUFFER
                        {
                            // Keep the framing anchor and frontier intact. The
                            // packet is forwarded, but a later retransmission can
                            // still supply a bounded frame again.
                            flow.pending.clear();
                            flow.pending_bytes = 0;
                            break;
                        }
                        chunks.push(ContiguousChunk {
                            bytes: payload[start..end].to_vec(),
                            current_range: Some(
                                (segment.payload_offset + payload_start + start)
                                    ..(segment.payload_offset + payload_start + end),
                            ),
                        });
                        flow.next_seq = flow.next_seq.wrapping_add((end - start) as u32);
                    }
                }
                drain_pending(flow, &mut chunks);
                consume_contiguous(flow, chunks, recorder, &mut context);
            }

            if segment.fin {
                flow.fin_seq
                    .get_or_insert(data_seq.wrapping_add(segment.payload.len() as u32));
            }
        }

        if segment.fin && segment.payload.is_empty() {
            if let Some(flow) = self.tcp_flows.get_mut(&segment.key) {
                flow.fin_seq
                    .get_or_insert(segment.seq.wrapping_add(u32::from(segment.syn)));
            }
        }
        let fin_consumed = self.tcp_flows.get(&segment.key).is_some_and(|flow| {
            flow.fin_seq.is_some_and(|fin_seq| {
                flow.next_seq == fin_seq || is_seq_after(flow.next_seq, fin_seq)
            })
        });
        if fin_consumed {
            self.tcp_flows.remove(&segment.key);
        }
        Some(context)
    }

    fn expire_pending(&mut self, now: Instant) {
        for flow in self.tcp_flows.values_mut() {
            if flow
                .pending
                .iter()
                .any(|segment| now.duration_since(segment.first_seen) >= TCP_DNS_GAP_TIMEOUT)
            {
                flow.pending.clear();
                flow.pending_bytes = 0;
            }
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
    payload_offset: usize,
    payload: &'a [u8],
}

fn is_seq_after(seq: u32, base: u32) -> bool {
    let distance = seq.wrapping_sub(base);
    distance != 0 && distance < TCP_SEQ_HALF_RANGE
}

fn seq_forward_offset(base: u32, seq: u32) -> Option<usize> {
    let distance = seq.wrapping_sub(base);
    (distance < TCP_SEQ_HALF_RANGE).then_some(distance as usize)
}

fn queue_pending(flow: &mut TcpDnsFlow, seq: u32, payload: &[u8], first_seen: Instant) {
    if payload.is_empty() {
        return;
    }

    // Keep the first bytes seen for every sequence range. Any overlap with an
    // existing segment, including conflicting bytes, is suppressed; only the
    // uncovered pieces of this later packet are eligible for buffering.
    let Some(incoming_start) = seq_forward_offset(flow.next_seq, seq) else {
        return;
    };
    let incoming_end = incoming_start.saturating_add(payload.len());
    let mut uncovered = vec![(incoming_start, incoming_end)];
    for existing in &flow.pending {
        let Some(existing_start) = seq_forward_offset(flow.next_seq, existing.seq) else {
            continue;
        };
        let existing_end = existing_start.saturating_add(existing.payload.len());
        let mut remaining = Vec::with_capacity(uncovered.len() + 1);
        for (start, end) in uncovered {
            if existing_end <= start || existing_start >= end {
                remaining.push((start, end));
                continue;
            }
            if start < existing_start {
                remaining.push((start, existing_start));
            }
            if existing_end < end {
                remaining.push((existing_end, end));
            }
        }
        uncovered = remaining;
        if uncovered.is_empty() {
            break;
        }
    }

    let pieces: Vec<(u32, Vec<u8>)> = uncovered
        .into_iter()
        .filter_map(|(start, end)| {
            let start_index = start.checked_sub(incoming_start)?;
            let end_index = end.checked_sub(incoming_start)?;
            (start_index < end_index).then(|| {
                (
                    flow.next_seq.wrapping_add(start as u32),
                    payload[start_index..end_index].to_vec(),
                )
            })
        })
        .collect();
    let piece_bytes: usize = pieces.iter().map(|(_, bytes)| bytes.len()).sum();
    if pieces.is_empty() {
        return;
    }
    if flow.pending.len().saturating_add(pieces.len()) > MAX_TCP_DNS_PENDING_SEGMENTS
        || flow.pending_bytes.saturating_add(piece_bytes) > MAX_TCP_DNS_PENDING_BYTES
    {
        // A full pending queue cannot safely describe the stream. Drop only
        // the speculative gap data; retain the framing anchor and frontier so
        // an exact retransmission at next_seq can recover the flow.
        flow.pending.clear();
        flow.pending_bytes = 0;
        return;
    }
    for (piece_seq, bytes) in pieces {
        flow.pending_bytes += bytes.len();
        flow.pending.push(TcpPendingSegment {
            seq: piece_seq,
            payload: bytes,
            first_seen,
        });
    }
}

fn drain_pending(flow: &mut TcpDnsFlow, chunks: &mut Vec<ContiguousChunk>) {
    loop {
        let mut remove_old = None;
        let mut candidate = None;
        for (index, pending) in flow.pending.iter().enumerate() {
            if pending.seq == flow.next_seq {
                candidate = Some(index);
                break;
            }
            if is_seq_after(flow.next_seq, pending.seq) {
                let overlap = flow.next_seq.wrapping_sub(pending.seq) as usize;
                if overlap >= pending.payload.len() {
                    remove_old = Some(index);
                    break;
                }
                candidate = Some(index);
                break;
            }
        }

        if let Some(index) = remove_old {
            let old = flow.pending.swap_remove(index);
            flow.pending_bytes -= old.payload.len();
            continue;
        }
        let Some(index) = candidate else { break };
        let pending = &flow.pending[index];
        let overlap = if is_seq_after(flow.next_seq, pending.seq) {
            flow.next_seq.wrapping_sub(pending.seq) as usize
        } else {
            0
        };
        let bytes_len = pending.payload.len().saturating_sub(overlap);
        let chunks_len: usize = chunks.iter().map(|chunk| chunk.bytes.len()).sum();
        if flow.buffer.len() + chunks_len + bytes_len > MAX_TCP_DNS_BUFFER {
            break;
        }
        let pending = flow.pending.swap_remove(index);
        flow.pending_bytes -= pending.payload.len();
        let bytes = pending.payload[overlap..].to_vec();
        if bytes.is_empty() {
            continue;
        }
        flow.next_seq = flow.next_seq.wrapping_add(bytes.len() as u32);
        chunks.push(ContiguousChunk {
            bytes,
            current_range: None,
        });
    }
}

fn consume_contiguous(
    flow: &mut TcpDnsFlow,
    chunks: Vec<ContiguousChunk>,
    recorder: &dyn DnsSink,
    context: &mut TcpRewriteContext,
) {
    if chunks.is_empty() {
        return;
    }
    if !flow.framing_known {
        for chunk in chunks {
            flow.buffer.extend_from_slice(&chunk.bytes);
        }
        return;
    }

    let old_len = flow.buffer.len();
    let added_len: usize = chunks.iter().map(|chunk| chunk.bytes.len()).sum();
    let mut combined = Vec::with_capacity(old_len + added_len);
    combined.extend_from_slice(&flow.buffer);
    let mut sources: Vec<Option<usize>> = vec![None; old_len];
    for chunk in chunks {
        if let Some(range) = &chunk.current_range {
            sources.extend((range.start..range.end).map(Some));
        } else {
            sources.extend(std::iter::repeat_n(None, chunk.bytes.len()));
        }
        combined.extend_from_slice(&chunk.bytes);
    }

    let mut cursor = 0usize;
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
        let msg = combined[cursor + 2..frame_end].to_vec();
        record_dns_answers(&msg, &SinkPolicy(recorder));

        let source_start = sources.get(cursor).and_then(|source| *source);
        let wholly_current = source_start.is_some()
            && (cursor..frame_end)
                .all(|index| sources[index] == source_start.map(|start| start + index - cursor));
        if wholly_current {
            if let Some(start) = source_start {
                context.frames.push((start + 2)..(start + 2 + msg_len));
            }
        }
        cursor = frame_end;
    }
    flow.buffer.clear();
    flow.buffer.extend_from_slice(&combined[cursor..]);
}

pub fn inspect_dns_response(packet: &[u8], recorder: &dyn DnsSink) {
    DnsInspector::default().inspect(packet, recorder);
}

impl DnsInspector {
    /// Inspects a decrypted packet and applies DNS policy before it reaches
    /// the TUN. UDP responses are recorded and rewritten in a single parse.
    /// TCP rewriting is deliberately coupled to this inspector's
    /// sequence/framing state, so callers must route TCP segments through
    /// this method rather than parsing them independently.
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
            let outcome = {
                let msg = &mut packet[view.dns_start..view.dns_end];
                process_response(msg, &SinkPolicy(policy))
            };
            let Outcome::Blanked { new_len, .. } = outcome else {
                return None;
            };
            let new_total = view.dns_start + new_len;
            repair_packet(packet, &view, new_total);
            return Some(new_total);
        }

        let tcp = &packet[view.transport_offset..view.ip_total_len];
        let context = self.inspect_tcp(packet, tcp, policy)?;
        let mut rewritten = false;
        for range in context.frames {
            let msg_start = view.transport_offset + range.start;
            let msg_end = view.transport_offset + range.end;
            if msg_end > packet.len() {
                continue;
            }
            let outcome = apply_policy(&mut packet[msg_start..msg_end], &SinkPolicy(policy));
            if matches!(outcome, Outcome::Blanked { .. }) {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    const DNS_TYPE_A: u16 = 1;
    const DNS_CLASS_IN: u16 = 1;
    const DNS_TYPE_HTTPS: u16 = 65;

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

    fn question_end(name: &str) -> usize {
        12 + dns_name(name).len() + 4
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

        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();
        let syn = ipv4_tcp_segment(&[], 999, 0x12);
        inspector.inspect(&syn, &sink);
        inspector.inspect(&packet, &sink);
        let answers = sink.0.lock().unwrap();
        assert_eq!(answers.len(), 1);
        assert_eq!(answers[0].0, "tracker.example");
        assert_eq!(answers[0].2, "203.0.113.7");
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
        let syn = ipv4_tcp_segment(&[], 999, 0x12);

        inspector.inspect(&syn, &sink);
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
        let syn = ipv4_tcp_segment(&[], 999, 0x12);

        inspector.inspect(&syn, &sink);
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
        let syn = ipv4_tcp_segment(&[], 999, 0x12);

        inspector.inspect(&syn, &sink);
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
    fn stateful_inspector_does_not_record_without_known_tcp_framing() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let packet = ipv4_tcp_segment(&tcp_dns_framed(&msg), 1000, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&packet, &sink);

        assert!(sink.0.lock().unwrap().is_empty());
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
    fn stateful_inspector_recovers_after_gap_retransmission() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let gap = ipv4_tcp_segment(&framed[split + 1..], 1000 + split as u32 + 1, 0x18);
        let retransmit = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&first, &sink);
        inspector.inspect(&gap, &sink);
        inspector.inspect(&retransmit, &sink);

        assert_eq!(sink.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn stateful_inspector_drains_out_of_order_segment() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let later = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x18);
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&later, &sink);
        assert!(sink.0.lock().unwrap().is_empty());
        inspector.inspect(&first, &sink);

        assert_eq!(sink.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn stateful_inspector_defers_out_of_order_fin_until_gap_is_filled() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let tail_with_fin = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x19);
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&tail_with_fin, &sink);
        assert!(sink.0.lock().unwrap().is_empty());
        assert_eq!(inspector.tcp_flows.len(), 1);

        inspector.inspect(&first, &sink);
        assert_eq!(sink.0.lock().unwrap().len(), 1);
        assert!(inspector.tcp_flows.is_empty());
    }

    #[test]
    fn stateful_inspector_drains_partial_overlap_tail() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let existing_end = split + 8;
        let overlap = 3;
        let existing = ipv4_tcp_segment(&framed[split..existing_end], 1000 + split as u32, 0x18);
        let incoming = ipv4_tcp_segment(
            &framed[split + overlap..],
            1000 + (split + overlap) as u32,
            0x18,
        );
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&existing, &sink);
        inspector.inspect(&incoming, &sink);
        inspector.inspect(&first, &sink);

        let records = sink.0.lock().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].2, "203.0.113.7");
    }

    #[test]
    fn stateful_inspector_drains_partial_overlap_head() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let existing_start = split + 8;
        let overlap = 3;
        let existing = ipv4_tcp_segment(
            &framed[existing_start..],
            1000 + existing_start as u32,
            0x18,
        );
        let incoming = ipv4_tcp_segment(
            &framed[split..existing_start + overlap],
            1000 + split as u32,
            0x18,
        );
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&existing, &sink);
        inspector.inspect(&incoming, &sink);
        inspector.inspect(&first, &sink);

        let records = sink.0.lock().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].2, "203.0.113.7");
    }

    #[test]
    fn stateful_inspector_preserves_first_seen_bytes_on_conflicting_overlap() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let later = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x18);
        let mut conflicting_bytes = framed[split..].to_vec();
        conflicting_bytes[0] ^= 0xff;
        let conflicting = ipv4_tcp_segment(&conflicting_bytes, 1000 + split as u32, 0x18);
        let first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&later, &sink);
        inspector.inspect(&conflicting, &sink);
        inspector.inspect(&first, &sink);

        let records = sink.0.lock().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].2, "203.0.113.7");
    }

    #[test]
    fn stateful_inspector_preserves_pending_bytes_during_gap_fill_overlap() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let pending = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x18);
        let mut gap_filler_bytes = framed.clone();
        let last = gap_filler_bytes.len() - 1;
        gap_filler_bytes[last] = 9;
        let gap_filler = ipv4_tcp_segment(&gap_filler_bytes, 1000, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&pending, &sink);
        inspector.inspect(&gap_filler, &sink);

        let records = sink.0.lock().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].2, "203.0.113.7");
    }

    #[test]
    fn pending_segment_cap_does_not_destroy_framing_anchor() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();
        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);

        for index in 0..=MAX_TCP_DNS_PENDING_SEGMENTS {
            let payload = [index as u8];
            let packet = ipv4_tcp_segment(&payload, 1100 + (index * 2) as u32, 0x18);
            inspector.inspect(&packet, &sink);
        }
        inspector.inspect(&ipv4_tcp_segment(&framed, 1000, 0x18), &sink);

        assert_eq!(sink.0.lock().unwrap().len(), 1);
        assert!(inspector
            .tcp_flows
            .values()
            .next()
            .expect("SYN-established flow")
            .pending
            .is_empty());
    }

    #[test]
    fn expired_pending_segment_allows_later_recovery() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let split = framed.len() / 2;
        let later = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x18);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();
        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&later, &sink);
        for flow in inspector.tcp_flows.values_mut() {
            for pending in &mut flow.pending {
                pending.first_seen = Instant::now() - TCP_DNS_GAP_TIMEOUT;
            }
        }

        inspector.inspect(&ipv4_tcp_segment(&framed, 1000, 0x18), &sink);

        assert_eq!(sink.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn framing_known_flow_survives_long_idle_at_frame_boundary() {
        let msg = dns_message(&[
            dns_question("tracker.example", DNS_TYPE_A),
            dns_answer_bytes("tracker.example", DNS_TYPE_A, 300, &[203, 0, 113, 7]),
        ]);
        let framed = tcp_dns_framed(&msg);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();
        inspector.inspect(&ipv4_tcp_segment(&[], 999, 0x12), &sink);
        inspector.inspect(&ipv4_tcp_segment(&framed, 1000, 0x18), &sink);
        for flow in inspector.tcp_flows.values_mut() {
            flow.last_seen = Instant::now() - Duration::from_secs(61);
        }
        inspector.inspect(
            &ipv4_tcp_segment(&framed, 1000 + framed.len() as u32, 0x18),
            &sink,
        );

        assert_eq!(sink.0.lock().unwrap().len(), 2);
    }

    #[test]
    fn out_of_order_frame_is_recorded_once_and_never_rewritten_retroactively() {
        let framed = tcp_dns_framed(&svcb_response());
        let split = framed.len() / 2;
        let sink = BlockingCollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();
        let mut syn = ipv4_tcp_segment(&[], 999, 0x12);
        inspector.inspect_and_rewrite(&mut syn, &sink);
        let mut later = ipv4_tcp_segment(&framed[split..], 1000 + split as u32, 0x18);
        let later_before = later.clone();
        assert_eq!(inspector.inspect_and_rewrite(&mut later, &sink), None);
        assert_eq!(later, later_before);
        let mut first = ipv4_tcp_segment(&framed[..split], 1000, 0x18);
        let first_before = first.clone();
        assert_eq!(inspector.inspect_and_rewrite(&mut first, &sink), None);
        assert_eq!(first, first_before);
        assert_eq!(sink.0.lock().unwrap().len(), 1);
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

    struct BlockingCollectingSink(Mutex<Vec<(String, String, String, i32)>>);

    impl DnsSink for BlockingCollectingSink {
        fn record_dns(&self, qname: &str, aname: &str, resource: &str, ttl: i32) {
            self.0.lock().unwrap().push((
                qname.to_owned(),
                aname.to_owned(),
                resource.to_owned(),
                ttl,
            ));
        }

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
        let question_end = question_end("tracker.example");
        let mut packet = ipv4_udp(&msg);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        // A non-zero incoming checksum exercises the rewrite path. IPv4 UDP
        // packets with checksum zero deliberately retain zero.
        packet[26..28].copy_from_slice(&0x1234u16.to_be_bytes());
        let new_len = inspector.inspect_and_rewrite(&mut packet, &sink).unwrap();
        // The A record is recorded before the response is blanked.
        assert_eq!(sink.0.lock().unwrap().len(), 1);

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
        let question_end = question_end("tracker.example");
        let mut packet = ipv6_udp_with_destination_options(&msg);
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        let new_len = inspector.inspect_and_rewrite(&mut packet, &sink).unwrap();
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
        let question_end = question_end("blocked.example");
        let mut packet = ipv4_udp(&msg);
        packet[26..28].copy_from_slice(&0x1234u16.to_be_bytes());
        let mut inspector = DnsInspector::default();

        let new_len = inspector
            .inspect_and_rewrite(&mut packet, &BlockingSink)
            .unwrap();
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
        let mut inspector = DnsInspector::default();

        assert_eq!(inspector.inspect_and_rewrite(&mut packet, &sink), None);
        assert_eq!(packet, before);
    }

    #[test]
    fn rewrite_non_in_svcb_answer_is_a_no_op() {
        let mut msg = dns_message(&[
            dns_question("ordinary.example", DNS_TYPE_A),
            dns_answer_bytes("ordinary.example", DNS_TYPE_HTTPS, 300, &[]),
        ]);
        let answer_name_end = question_end("ordinary.example") + dns_name("ordinary.example").len();
        msg[answer_name_end + 2..answer_name_end + 4].copy_from_slice(&2u16.to_be_bytes());
        let mut packet = ipv4_udp(&msg);
        let before = packet.clone();
        let sink = CollectingSink(Mutex::new(Vec::new()));
        let mut inspector = DnsInspector::default();

        assert_eq!(inspector.inspect_and_rewrite(&mut packet, &sink), None);
        assert_eq!(packet, before);
    }
}

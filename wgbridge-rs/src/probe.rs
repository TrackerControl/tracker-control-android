//! Bounded, in-tunnel DNS reachability probes. No socket sends bypass the VPN.
//! The UDP socket below only reserves a source port; the packet enters gotatun
//! through IpRecv and its correlated reply is consumed before reaching Android.

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

const QUESTION: [u8; 5] = [0, 0, 2, 0, 1]; // root NS IN; no user hostname
pub const PROBE_LIFETIME: Duration = Duration::from_secs(5);

struct Pending {
    source: IpAddr,
    resolver: IpAddr,
    port: u16,
    id: [u8; 2],
    token: i64,
    expires: Instant,
    _reservation: UdpSocket,
}

#[derive(Default)]
pub struct ProbeTracker {
    active: AtomicBool,
    pending: Mutex<Option<Pending>>,
    reply_token: AtomicI64,
}

impl ProbeTracker {
    pub fn reply_token(&self) -> i64 {
        self.reply_token.load(Ordering::Acquire)
    }

    pub fn prepare(&self, source: IpAddr, resolver: IpAddr, token: i64) -> io::Result<Vec<u8>> {
        if source.is_ipv4() != resolver.is_ipv4()
            || source.is_unspecified()
            || source.is_multicast()
            || resolver.is_unspecified()
            || resolver.is_multicast()
            || resolver.is_loopback()
            || token <= 0
        {
            return Err(io::Error::other("invalid DNS probe addresses or token"));
        }
        let bind_ip = match source {
            IpAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            IpAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        };
        let reservation = UdpSocket::bind(SocketAddr::new(bind_ip, 0))?;
        let port = reservation.local_addr()?.port();
        let mut id = [0; 2];
        getrandom::fill(&mut id).map_err(io::Error::other)?;
        let packet = dns_packet(source, resolver, port, id);
        let mut pending = self
            .pending
            .lock()
            .map_err(|_| io::Error::other("probe lock poisoned"))?;
        *pending = Some(Pending {
            source,
            resolver,
            port,
            id,
            token,
            expires: Instant::now() + PROBE_LIFETIME,
            _reservation: reservation,
        });
        self.active.store(true, Ordering::Release);
        Ok(packet)
    }

    pub fn expire(&self) {
        if let Ok(mut pending) = self.pending.lock() {
            if pending
                .as_ref()
                .is_some_and(|p| Instant::now() >= p.expires)
            {
                *pending = None;
                self.active.store(false, Ordering::Release);
            }
        }
    }

    /// Returns true only for our current, unexpired DNS transaction. Ordinary
    /// application packets take one atomic load when no probe is outstanding.
    pub fn consume_reply(&self, packet: &[u8]) -> bool {
        if !self.active.load(Ordering::Acquire) {
            return false;
        }
        let Ok(mut pending) = self.pending.lock() else {
            return false;
        };
        let Some(probe) = pending.as_ref() else {
            return false;
        };
        if Instant::now() >= probe.expires {
            *pending = None;
            self.active.store(false, Ordering::Release);
            return false;
        }
        let Some((source, dest, udp)) = udp_payload(packet) else {
            return false;
        };
        if source != probe.resolver
            || dest != probe.source
            || udp.len() < 25
            || udp[0..2] != 53u16.to_be_bytes()
            || udp[2..4] != probe.port.to_be_bytes()
            || usize::from(u16::from_be_bytes([udp[4], udp[5]])) != udp.len()
        {
            return false;
        }
        let dns = &udp[8..];
        if dns[0..2] != probe.id
            || dns[2] & 0xf8 != 0x80
            || dns[4..6] != [0, 1]
            || dns[12..17] != QUESTION
        {
            return false;
        }
        // Any DNS rcode proves a round trip; this is not a resolver health test.
        self.reply_token.store(probe.token, Ordering::Release);
        *pending = None;
        self.active.store(false, Ordering::Release);
        true
    }
}

fn udp_payload(packet: &[u8]) -> Option<(IpAddr, IpAddr, &[u8])> {
    match packet.first()? >> 4 {
        4 if packet.len() >= 20 => {
            let header = usize::from(packet[0] & 15) * 4;
            let len = usize::from(u16::from_be_bytes([packet[2], packet[3]]));
            if header < 20
                || len != packet.len()
                || header > len
                || packet[9] != 17
                || u16::from_be_bytes([packet[6], packet[7]]) & 0x3fff != 0
            {
                return None;
            }
            Some((
                Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]).into(),
                Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]).into(),
                &packet[header..],
            ))
        }
        6 if packet.len() >= 40 => {
            if packet[6] != 17
                || usize::from(u16::from_be_bytes([packet[4], packet[5]])) + 40 != packet.len()
            {
                return None;
            }
            let source: [u8; 16] = packet[8..24].try_into().ok()?;
            let dest: [u8; 16] = packet[24..40].try_into().ok()?;
            Some((
                Ipv6Addr::from(source).into(),
                Ipv6Addr::from(dest).into(),
                &packet[40..],
            ))
        }
        _ => None,
    }
}

fn checksum(bytes: &[u8]) -> u16 {
    let sum = bytes.chunks(2).fold(0u32, |sum, pair| {
        sum + (u32::from(pair[0]) << 8) + u32::from(*pair.get(1).unwrap_or(&0))
    });
    let sum = (sum & 0xffff) + (sum >> 16);
    !((sum & 0xffff) + (sum >> 16)) as u16
}

fn dns_packet(source: IpAddr, resolver: IpAddr, port: u16, id: [u8; 2]) -> Vec<u8> {
    let mut udp = vec![0u8; 25];
    udp[0..2].copy_from_slice(&port.to_be_bytes());
    udp[2..4].copy_from_slice(&53u16.to_be_bytes());
    udp[4..6].copy_from_slice(&25u16.to_be_bytes());
    udp[8..10].copy_from_slice(&id);
    udp[10] = 1; // recursion desired
    udp[13] = 1; // one question
    udp[20..25].copy_from_slice(&QUESTION);
    let mut pseudo = Vec::new();
    let mut ip = match (source, resolver) {
        (IpAddr::V4(src), IpAddr::V4(dst)) => {
            let mut ip = vec![0u8; 20];
            ip[0] = 0x45;
            ip[2..4].copy_from_slice(&45u16.to_be_bytes());
            ip[8] = 64;
            ip[9] = 17;
            ip[12..16].copy_from_slice(&src.octets());
            ip[16..20].copy_from_slice(&dst.octets());
            let check = checksum(&ip);
            ip[10..12].copy_from_slice(&check.to_be_bytes());
            pseudo.extend_from_slice(&src.octets());
            pseudo.extend_from_slice(&dst.octets());
            pseudo.extend_from_slice(&[0, 17, 0, 25]);
            ip
        }
        (IpAddr::V6(src), IpAddr::V6(dst)) => {
            let mut ip = vec![0u8; 40];
            ip[0] = 0x60;
            ip[4..6].copy_from_slice(&25u16.to_be_bytes());
            ip[6] = 17;
            ip[7] = 64;
            ip[8..24].copy_from_slice(&src.octets());
            ip[24..40].copy_from_slice(&dst.octets());
            pseudo.extend_from_slice(&src.octets());
            pseudo.extend_from_slice(&dst.octets());
            pseudo.extend_from_slice(&[0, 0, 0, 25, 0, 0, 0, 17]);
            ip
        }
        _ => return Vec::new(), // prepare rejects mixed families
    };
    pseudo.extend_from_slice(&udp);
    let check = checksum(&pseudo);
    udp[6..8].copy_from_slice(&(if check == 0 { 0xffff } else { check }).to_be_bytes());
    ip.extend_from_slice(&udp);
    ip
}

#[cfg(test)]
pub(crate) fn test_reply(query: &[u8]) -> Vec<u8> {
    let mut reply = query.to_vec();
    let header = if query[0] >> 4 == 4 { 20 } else { 40 };
    if header == 20 {
        reply[12..16].copy_from_slice(&query[16..20]);
        reply[16..20].copy_from_slice(&query[12..16]);
    } else {
        reply[8..24].copy_from_slice(&query[24..40]);
        reply[24..40].copy_from_slice(&query[8..24]);
    }
    reply[header..header + 2].copy_from_slice(&query[header + 2..header + 4]);
    reply[header + 2..header + 4].copy_from_slice(&query[header..header + 2]);
    reply[header + 10] |= 0x80;
    reply
}

#[cfg(test)]
mod tests {
    use super::*;

    fn addresses(v6: bool) -> (IpAddr, IpAddr) {
        if v6 {
            (
                "2001:db8::2".parse().unwrap(),
                "2001:db8::53".parse().unwrap(),
            )
        } else {
            ("10.0.0.2".parse().unwrap(), "10.0.0.53".parse().unwrap())
        }
    }

    #[test]
    fn queries_have_valid_ip_and_udp_checksums_in_both_families() {
        for v6 in [false, true] {
            let (source, resolver) = addresses(v6);
            let packet = dns_packet(source, resolver, 40000, [0x12, 0x34]);
            let header = if v6 { 40 } else { 20 };
            let mut pseudo = Vec::new();
            if v6 {
                pseudo.extend_from_slice(&packet[8..40]);
                pseudo.extend_from_slice(&[0, 0, 0, 25, 0, 0, 0, 17]);
            } else {
                assert_eq!(checksum(&packet[..20]), 0);
                pseudo.extend_from_slice(&packet[12..20]);
                pseudo.extend_from_slice(&[0, 17, 0, 25]);
            }
            pseudo.extend_from_slice(&packet[header..]);
            assert_eq!(checksum(&pseudo), 0);
            assert_eq!(&packet[header + 20..], &QUESTION);
            assert_eq!(&packet[header + 8..header + 10], &[0x12, 0x34]);
        }
    }

    #[test]
    fn only_current_correlated_reply_is_consumed_once() {
        for v6 in [false, true] {
            let tracker = ProbeTracker::default();
            let (source, resolver) = addresses(v6);
            let query = tracker.prepare(source, resolver, 11).unwrap();
            assert!(!tracker.consume_reply(&query));
            let reply = test_reply(&query);
            let header = if v6 { 40 } else { 20 };
            for index in [header, header + 2, header + 4, header + 8, header + 20] {
                let mut malformed = reply.clone();
                malformed[index] ^= 1;
                assert!(!tracker.consume_reply(&malformed));
            }
            for len in 0..reply.len() {
                assert!(!tracker.consume_reply(&reply[..len]));
            }
            assert_eq!(tracker.reply_token(), 0);
            assert!(tracker.consume_reply(&reply));
            assert_eq!(tracker.reply_token(), 11);
            assert!(!tracker.consume_reply(&reply));
        }
    }

    #[test]
    fn replaced_and_expired_probes_cannot_confirm_new_attempts() {
        let tracker = ProbeTracker::default();
        let (source, resolver) = addresses(false);
        let old = tracker.prepare(source, resolver, 1).unwrap();
        let new = tracker.prepare(source, resolver, 2).unwrap();
        assert!(!tracker.consume_reply(&test_reply(&old)));
        tracker.pending.lock().unwrap().as_mut().unwrap().expires = Instant::now();
        assert!(!tracker.consume_reply(&test_reply(&new)));
        assert_eq!(tracker.reply_token(), 0);
        assert!(!tracker.active.load(Ordering::Acquire));
        tracker.prepare(source, resolver, 3).unwrap();
        tracker.pending.lock().unwrap().as_mut().unwrap().expires = Instant::now();
        tracker.expire();
        assert!(tracker.pending.lock().unwrap().is_none());
    }

    #[test]
    fn invalid_probe_addresses_are_rejected() {
        let (source, resolver) = addresses(false);
        let tracker = ProbeTracker::default();
        for invalid in ["::1", "0.0.0.0", "127.0.0.1", "224.0.0.1"] {
            assert!(tracker
                .prepare(source, invalid.parse().unwrap(), 1)
                .is_err());
        }
        assert!(tracker.prepare(source, resolver, 0).is_err());
    }
}

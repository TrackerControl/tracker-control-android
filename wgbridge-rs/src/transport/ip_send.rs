//! IpSend to the VpnService TUN fd, with DNS inspection and response policy
//! applied to decrypted inbound packets on the way through.

use std::io;
use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use gotatun::packet::{Ip, Packet};
use gotatun::tun::IpSend;

use crate::callbacks::DnsSink;
use crate::dns::DnsInspector;

pub struct TunFdSend {
    fd: Arc<OwnedFd>,
    dns: Option<Arc<dyn DnsSink>>,
    dns_inspector: DnsInspector,
    write_failures_total: Arc<AtomicU64>,
    write_failures_streak: Arc<AtomicU64>,
}

impl TunFdSend {
    /// Takes ownership of `fd` (already a private dup of the VpnService TUN fd).
    pub fn new(fd: OwnedFd, dns: Option<Arc<dyn DnsSink>>) -> Self {
        Self::with_counters(
            fd,
            dns,
            Arc::new(AtomicU64::new(0)),
            Arc::new(AtomicU64::new(0)),
        )
    }

    /// Takes ownership of `fd` and shares TUN write counters with the owning
    /// [`crate::tunnel::Tunnel`].
    pub fn with_counters(
        fd: OwnedFd,
        dns: Option<Arc<dyn DnsSink>>,
        write_failures_total: Arc<AtomicU64>,
        write_failures_streak: Arc<AtomicU64>,
    ) -> Self {
        Self {
            fd: Arc::new(fd),
            dns,
            dns_inspector: DnsInspector::default(),
            write_failures_total,
            write_failures_streak,
        }
    }
}

fn write_fd(fd: i32, buf: &[u8]) -> isize {
    // SAFETY: buf is valid for reads of buf.len() bytes.
    unsafe { libc::write(fd, buf.as_ptr() as *const libc::c_void, buf.len()) }
}

fn record_tun_write(total: &AtomicU64, streak: &AtomicU64, full_write: bool) -> (u64, u64) {
    if full_write {
        streak.store(0, Ordering::Relaxed);
        (total.load(Ordering::Relaxed), 0)
    } else {
        let total = total.fetch_add(1, Ordering::Relaxed) + 1;
        let streak = streak.fetch_add(1, Ordering::Relaxed) + 1;
        (total, streak)
    }
}

impl IpSend for TunFdSend {
    async fn send(&mut self, packet: Packet<Ip>) -> io::Result<()> {
        let mut packet: Packet<[u8]> = packet.into();

        if let Some(dns) = &self.dns {
            // The inspector records A/AAAA mappings before it blanks
            // SVCB/HTTPS or a domain-blocked response. Its TCP sequence state
            // also prevents continuation segments from being parsed as new
            // DNS-over-TCP frames.
            let data = packet.buf_mut().as_mut();
            if let Some(new_len) = self.dns_inspector.inspect_and_rewrite(data, dns.as_ref()) {
                packet.truncate(new_len);
            }
        }

        let data = packet.as_ref();

        let n = write_fd(self.fd.as_raw_fd(), data);
        let (errors, streak) = record_tun_write(
            &self.write_failures_total,
            &self.write_failures_streak,
            n == data.len() as isize,
        );
        if n != data.len() as isize {
            // TUN write failures are transient (e.g. ENOBUFS under load) or
            // mean the VPN is being torn down, in which case Java stops us.
            // Never bubble them up: gotatun treats IpSend errors as fatal.
            if errors % 1024 == 1 {
                log::warn!(
                    "tun write failed ({n}/{} bytes, {} total failures, {} consecutive): {}",
                    data.len(),
                    errors,
                    streak,
                    io::Error::last_os_error()
                );
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::{record_tun_write, TunFdSend};
    use gotatun::packet::{Ip, Packet};
    use gotatun::tun::IpSend;
    use std::fs::OpenOptions;
    use std::os::fd::{FromRawFd, OwnedFd};
    use std::sync::atomic::AtomicU64;
    use std::sync::atomic::Ordering;
    use std::sync::Arc;

    fn minimal_ipv4_packet() -> (Packet<Ip>, [u8; 20]) {
        let bytes = [
            0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x01, 0xf9, 0x95, 0xc0,
            0xa8, 0x00, 0x01, 0xc0, 0xa8, 0x00, 0x02,
        ];
        Packet::copy_from(bytes.as_slice())
            .try_into_ipvx()
            .expect("minimal IPv4 packet should pass full validation");
        let packet = Packet::copy_from(bytes.as_slice())
            .try_into_ip()
            .expect("minimal IPv4 packet should parse");
        (packet, bytes)
    }

    #[test]
    fn write_counter_transition_resets_streak_after_full_write() {
        let total = Arc::new(AtomicU64::new(0));
        let streak = Arc::new(AtomicU64::new(0));
        assert_eq!(record_tun_write(&total, &streak, false), (1, 1));
        assert_eq!(record_tun_write(&total, &streak, false), (2, 2));

        assert_eq!(record_tun_write(&total, &streak, true), (2, 0));
        assert_eq!(total.load(Ordering::Relaxed), 2);
        assert_eq!(streak.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn send_forwards_full_packet_and_resets_streak() {
        let mut fds = [0; 2];
        assert_eq!(
            unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_DGRAM, 0, fds.as_mut_ptr()) },
            0
        );
        let reader = unsafe { OwnedFd::from_raw_fd(fds[0]) };
        let writer = unsafe { OwnedFd::from_raw_fd(fds[1]) };
        let total = Arc::new(AtomicU64::new(7));
        let streak = Arc::new(AtomicU64::new(3));
        let mut sender = TunFdSend::with_counters(
            writer,
            None,
            Arc::clone(&total),
            Arc::clone(&streak),
        );
        let (packet, expected) = minimal_ipv4_packet();

        assert!(sender.send(packet).await.is_ok());
        assert_eq!(total.load(Ordering::Relaxed), 7);
        assert_eq!(streak.load(Ordering::Relaxed), 0);

        let reader = std::os::unix::net::UnixDatagram::from(reader);
        reader.set_read_timeout(Some(std::time::Duration::from_secs(1))).unwrap();
        let mut received = [0; 20];
        assert_eq!(reader.recv(&mut received).unwrap(), received.len());
        assert_eq!(received, expected);
    }

    #[tokio::test]
    async fn send_keeps_failed_write_nonfatal_and_updates_counters() {
        let file = OpenOptions::new()
            .read(true)
            .open("/dev/null")
            .unwrap();
        let fd: OwnedFd = file.into();
        let total = Arc::new(AtomicU64::new(5));
        let streak = Arc::new(AtomicU64::new(2));
        let mut sender = TunFdSend::with_counters(
            fd,
            None,
            Arc::clone(&total),
            Arc::clone(&streak),
        );

        for _ in 0..3 {
            let (packet, _) = minimal_ipv4_packet();
            assert!(sender.send(packet).await.is_ok());
        }

        assert_eq!(total.load(Ordering::Relaxed), 8);
        assert_eq!(streak.load(Ordering::Relaxed), 5);
    }
}

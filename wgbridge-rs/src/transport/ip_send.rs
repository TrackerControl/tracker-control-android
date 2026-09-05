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
    use super::record_tun_write;
    use std::sync::atomic::AtomicU64;
    use std::sync::atomic::Ordering;
    use std::sync::Arc;

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
}

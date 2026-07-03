//! IpSend to the VpnService TUN fd, with passive DNS inspection of the
//! decrypted inbound packets on the way through.

use std::io;
use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use gotatun::packet::{Ip, Packet};
use gotatun::tun::IpSend;

use crate::callbacks::DnsSink;
use crate::dns::inspect_dns_response;

pub struct TunFdSend {
    fd: Arc<OwnedFd>,
    dns: Option<Arc<dyn DnsSink>>,
    write_errors: Arc<AtomicU64>,
}

impl TunFdSend {
    /// Takes ownership of `fd` (already a private dup of the VpnService TUN fd).
    pub fn new(fd: OwnedFd, dns: Option<Arc<dyn DnsSink>>) -> Self {
        Self {
            fd: Arc::new(fd),
            dns,
            write_errors: Arc::new(AtomicU64::new(0)),
        }
    }
}

fn write_fd(fd: i32, buf: &[u8]) -> isize {
    // SAFETY: buf is valid for reads of buf.len() bytes.
    unsafe { libc::write(fd, buf.as_ptr() as *const libc::c_void, buf.len()) }
}

impl IpSend for TunFdSend {
    async fn send(&mut self, packet: Packet<Ip>) -> io::Result<()> {
        let packet: Packet<[u8]> = packet.into();
        let data = packet.as_ref();

        if let Some(dns) = &self.dns {
            inspect_dns_response(data, dns.as_ref());
        }

        let n = write_fd(self.fd.as_raw_fd(), data);
        if n != data.len() as isize {
            // TUN write failures are transient (e.g. ENOBUFS under load) or
            // mean the VPN is being torn down, in which case Java stops us.
            // Never bubble them up: gotatun treats IpSend errors as fatal.
            let errors = self.write_errors.fetch_add(1, Ordering::Relaxed) + 1;
            if errors % 1024 == 1 {
                log::warn!(
                    "tun write failed ({n}/{} bytes, {} total failures): {}",
                    data.len(),
                    errors,
                    io::Error::last_os_error()
                );
            }
        }
        Ok(())
    }
}

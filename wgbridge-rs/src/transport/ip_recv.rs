//! IpRecv over the socketpair read-end: the C side writes one raw IP packet
//! per datagram (SOCK_DGRAM preserves boundaries), we batch-drain them.

use std::io;
use std::os::fd::{AsRawFd, OwnedFd};

use gotatun::packet::{Ip, Packet, PacketBufPool};
use gotatun::tun::{IpRecv, MtuWatcher};
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;

/// Upper bound on packets drained per wakeup so one busy burst can't starve
/// the executor.
const MAX_BATCH: usize = 32;

pub struct SocketpairRecv {
    afd: AsyncFd<OwnedFd>,
    mtu: MtuWatcher,
}

impl SocketpairRecv {
    /// Takes ownership of `fd` (already a private dup). Sets it non-blocking
    /// for use with the tokio reactor.
    pub fn new(fd: OwnedFd, mtu: u16) -> io::Result<Self> {
        set_nonblocking(&fd)?;
        Ok(Self {
            afd: AsyncFd::with_interest(fd, Interest::READABLE)?,
            mtu: MtuWatcher::new(mtu),
        })
    }
}

fn set_nonblocking(fd: &OwnedFd) -> io::Result<()> {
    // SAFETY: fd is a valid owned descriptor for the duration of the calls.
    unsafe {
        let flags = libc::fcntl(fd.as_raw_fd(), libc::F_GETFL);
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        if libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

fn read_fd(fd: i32, buf: &mut [u8]) -> isize {
    // SAFETY: buf is valid for writes of buf.len() bytes.
    unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) }
}

impl IpRecv for SocketpairRecv {
    async fn recv<'a>(
        &'a mut self,
        pool: &mut PacketBufPool,
    ) -> io::Result<impl Iterator<Item = Packet<Ip>> + Send + 'a> {
        loop {
            let mut guard = self.afd.readable().await?;
            let fd = self.afd.get_ref().as_raw_fd();

            let mut packets: Vec<Packet<Ip>> = Vec::new();
            loop {
                if packets.len() >= MAX_BATCH {
                    break;
                }
                let mut packet = pool.get();
                let n = read_fd(fd, packet.buf_mut());
                if n < 0 {
                    let e = io::Error::last_os_error();
                    if e.kind() == io::ErrorKind::WouldBlock {
                        guard.clear_ready();
                        break;
                    }
                    // Retryable; must not bubble up — gotatun treats an IpRecv
                    // error as fatal and permanently stops the outbound pump.
                    if e.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    if packets.is_empty() {
                        return Err(e);
                    }
                    break;
                }
                if n == 0 {
                    // Socketpair write-end closed: the C side shut down.
                    if packets.is_empty() {
                        return Err(io::Error::new(
                            io::ErrorKind::UnexpectedEof,
                            "wg socketpair closed",
                        ));
                    }
                    break;
                }
                packet.truncate(n as usize);
                match packet.try_into_ip() {
                    Ok(ip) => packets.push(ip),
                    Err(_) => continue, // drop malformed packet, keep draining
                }
            }

            if !packets.is_empty() {
                return Ok(packets.into_iter());
            }
            // Nothing (valid) read; wait for the next readiness event.
        }
    }

    fn mtu(&self) -> MtuWatcher {
        self.mtu.clone()
    }
}

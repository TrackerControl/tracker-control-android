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
    probes: tokio::sync::mpsc::Receiver<Vec<u8>>,
}

impl SocketpairRecv {
    /// Takes ownership of `fd` (already a private dup). Sets it non-blocking
    /// for use with the tokio reactor.
    pub fn new(fd: OwnedFd, mtu: u16) -> io::Result<Self> {
        let (_, probes) = tokio::sync::mpsc::channel(1);
        Self::with_probes(fd, mtu, probes)
    }

    pub fn with_probes(fd: OwnedFd, mtu: u16, probes: tokio::sync::mpsc::Receiver<Vec<u8>>) -> io::Result<Self> {
        set_nonblocking(&fd)?;
        Ok(Self {
            afd: AsyncFd::with_interest(fd, Interest::READABLE)?,
            mtu: MtuWatcher::new(mtu),
            probes,
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
            let mut guard = tokio::select! {
                Some(bytes) = self.probes.recv() => {
                    if let Ok(packet) = Packet::copy_from(bytes.as_slice()).try_into_ip() {
                        return Ok(vec![packet].into_iter());
                    }
                    continue;
                }
                ready = self.afd.readable() => ready?,
            };
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::net::UnixDatagram;
    use std::time::Duration;

    #[tokio::test]
    async fn probes_and_normal_packets_share_recv_without_channel_close_stopping_it() {
        let (writer, reader) = UnixDatagram::pair().unwrap();
        let (tx, rx) = tokio::sync::mpsc::channel(1);
        let mut recv = SocketpairRecv::with_probes(reader.into(), 1280, rx).unwrap();
        let tracker = crate::probe::ProbeTracker::default();
        let query = tracker.prepare("10.0.0.2".parse().unwrap(), "10.0.0.53".parse().unwrap(), 1).unwrap();
        tx.send(query.clone()).await.unwrap();
        let mut pool = PacketBufPool::new(2);
        {
            let mut packets = tokio::time::timeout(Duration::from_secs(1), recv.recv(&mut pool)).await.unwrap().unwrap();
            let packet: Packet<[u8]> = packets.next().unwrap().into();
            assert_eq!(packet.as_ref(), query.as_slice());
            assert!(packets.next().is_none());
        }
        drop(tx);
        writer.send(&query).unwrap();
        let mut packets = tokio::time::timeout(Duration::from_secs(1), recv.recv(&mut pool)).await.unwrap().unwrap();
        let packet: Packet<[u8]> = packets.next().unwrap().into();
        assert_eq!(packet.as_ref(), query.as_slice());
    }
}

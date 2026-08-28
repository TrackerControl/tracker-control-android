//! UdpTransportFactory that wraps gotatun's stock socket factory and
//! VpnService.protect()s every socket it binds. Because the factory is
//! re-invoked on every Connection::set_up, a forced device reconfigure
//! doubles as "rebind the UDP sockets on the new default network".

use std::io;
use std::os::fd::AsRawFd;
use std::sync::Arc;

use gotatun::udp::socket::{UdpSocket, UdpSocketFactory};
use gotatun::udp::{UdpTransportFactory, UdpTransportFactoryParams};

use crate::callbacks::SocketProtector;

pub struct ProtectedUdpFactory {
    inner: UdpSocketFactory,
    protector: Arc<dyn SocketProtector>,
}

impl ProtectedUdpFactory {
    pub fn new(protector: Arc<dyn SocketProtector>) -> Self {
        Self {
            inner: UdpSocketFactory::default(),
            protector,
        }
    }

    fn protect(&self, socket: &UdpSocket) -> io::Result<()> {
        // gotatun hides the inner socket behind a struct; socket() exposes the
        // underlying tokio UdpSocket so we can protect its fd.
        let fd = socket.socket().as_raw_fd();
        if !self.protector.protect(fd) {
            // Fail closed: an unprotected socket would loop through the TUN.
            return Err(io::Error::other(format!(
                "VpnService.protect({fd}) failed"
            )));
        }
        Ok(())
    }
}

impl UdpTransportFactory for ProtectedUdpFactory {
    type Send = UdpSocket;
    type Recv = UdpSocket;

    async fn bind(
        &mut self,
        params: &UdpTransportFactoryParams,
    ) -> io::Result<(Self::Send, Self::Recv)> {
        // gotatun 0.9 binds a single dual-stack socket; send/recv are clones of
        // it, so protecting one half protects both.
        let (send, recv) = self.inner.bind(params).await?;
        self.protect(&send)?;
        log::info!("bound and protected WG UDP socket");
        Ok((send, recv))
    }
}

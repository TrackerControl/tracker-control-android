//! UdpTransportFactory that wraps gotatun's stock socket factory and
//! VpnService.protect()s every socket it binds. Because the factory is
//! re-invoked on every Connection::set_up, a forced device reconfigure
//! doubles as "rebind the UDP sockets on the new default network".

use std::io;
use std::os::fd::{AsFd, AsRawFd};
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
        let fd = socket.as_fd().as_raw_fd();
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
    type SendV4 = UdpSocket;
    type SendV6 = UdpSocket;
    type RecvV4 = UdpSocket;
    type RecvV6 = UdpSocket;

    async fn bind(
        &mut self,
        params: &UdpTransportFactoryParams,
    ) -> io::Result<((Self::SendV4, Self::RecvV4), (Self::SendV6, Self::RecvV6))> {
        let ((send_v4, recv_v4), (send_v6, recv_v6)) = self.inner.bind(params).await?;
        // send/recv halves are clones of the same socket; protect each family once.
        self.protect(&send_v4)?;
        self.protect(&send_v6)?;
        log::info!("bound and protected WG UDP sockets");
        Ok(((send_v4, recv_v4), (send_v6, recv_v6)))
    }
}

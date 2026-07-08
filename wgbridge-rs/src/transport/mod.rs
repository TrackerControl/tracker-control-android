//! Custom gotatun transports for TrackerControl's packet path:
//! outbound IP packets arrive on a Unix socketpair written by the C tunnel
//! thread (jni/netguard/ip.c), decrypted inbound packets go straight to the
//! VpnService TUN fd, and the outer UDP sockets are VpnService.protect()-ed.

mod ip_recv;
mod ip_send;
mod udp;

pub use ip_recv::SocketpairRecv;
pub use ip_send::TunFdSend;
pub use udp::ProtectedUdpFactory;

/// The DeviceTransports tuple used by the bridge.
pub type Transports = (ProtectedUdpFactory, TunFdSend, SocketpairRecv);

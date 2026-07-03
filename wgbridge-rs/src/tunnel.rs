//! Tunnel lifecycle: owns the tokio runtime and the gotatun Device, and
//! exposes the blocking API surface that the JNI layer forwards to Java
//! (start/stop/setConfig/stats/sendKeepalive/rebind/updateEndpoint).

use std::io;
use std::os::fd::{FromRawFd, OwnedFd};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use gotatun::device::Device;
use gotatun::x25519::PublicKey;
use tokio::runtime::Runtime;

use crate::callbacks::{BridgeLogger, DnsSink, SocketProtector};
use crate::config::{parse_uapi_config, PeerConfig};
use crate::transport::{ProtectedUdpFactory, SocketpairRecv, Transports, TunFdSend};

/// Interval the keepalive prod temporarily applies to force traffic (and, if
/// the session expired, a fresh handshake) on a stalled tunnel.
const PROD_KEEPALIVE_SECS: u16 = 1;
/// How long the prod interval stays before the configured value is restored.
const PROD_RESTORE_AFTER: Duration = Duration::from_secs(3);

pub struct TunnelStats {
    pub rx_bytes: i64,
    pub tx_bytes: i64,
    pub latest_handshake_millis: i64,
}

struct Inner {
    device: tokio::sync::Mutex<Option<Device<Transports>>>,
    /// Peer configuration as last applied; consulted when restoring the
    /// keepalive interval after a prod.
    peers: std::sync::Mutex<Vec<PeerConfig>>,
    logger: Arc<dyn BridgeLogger>,
}

pub struct Tunnel {
    runtime: Runtime,
    inner: Arc<Inner>,
}

fn dup_fd(fd: i32) -> io::Result<OwnedFd> {
    // SAFETY: dup returns a fresh descriptor we exclusively own; the caller
    // keeps ownership of the original.
    let duped = unsafe { libc::dup(fd) };
    if duped < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { OwnedFd::from_raw_fd(duped) })
}

/// Boots gotatun.
///
/// * `uapi_config`   – UAPI text produced by WgConfig.toUapi() on the Java side.
/// * `outbound_rx_fd`– read end of a socketpair held by C; C writes raw IP
///                     packets, gotatun reads them as its "TUN".
/// * `tun_write_fd`  – VpnService TUN fd; decrypted inbound IP packets are
///                     written here.
/// * `mtu`           – payload MTU (typically 1420).
/// * `protector`     – Java callback to VpnService.protect(int).
/// * `logger`        – Java callback for bridge log lines.
/// * `dns`           – Java callback for passive DNS answer recording (may be None).
///
/// The supplied fds are duplicated; stop() closes only our duplicates.
pub fn start_tunnel(
    uapi_config: &str,
    outbound_rx_fd: i32,
    tun_write_fd: i32,
    mtu: u16,
    protector: Arc<dyn SocketProtector>,
    logger: Arc<dyn BridgeLogger>,
    dns: Option<Arc<dyn DnsSink>>,
) -> Result<Tunnel, String> {
    let config = parse_uapi_config(uapi_config).map_err(|e| e.to_string())?;

    let rx_fd = dup_fd(outbound_rx_fd).map_err(|e| format!("dup outbound fd: {e}"))?;
    let tx_fd = dup_fd(tun_write_fd).map_err(|e| format!("dup tun fd: {e}"))?;

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("wgbridge")
        .enable_all()
        .build()
        .map_err(|e| format!("tokio runtime: {e}"))?;

    let device = runtime
        .block_on(async {
            let ip_recv = SocketpairRecv::new(rx_fd, mtu)?;
            let ip_send = TunFdSend::new(tx_fd, dns);
            gotatun::device::build()
                .with_udp(ProtectedUdpFactory::new(protector))
                .with_ip_pair(ip_send, ip_recv)
                .with_private_key(config.private_key.clone())
                .with_peers(config.peers.iter().map(PeerConfig::to_peer))
                .build()
                .await
                .map_err(|e| io::Error::other(e.to_string()))
        })
        .map_err(|e| format!("device up: {e}"))?;

    logger.verbose(&format!(
        "gotatun device up ({} peer(s), mtu {mtu})",
        config.peers.len()
    ));

    Ok(Tunnel {
        runtime,
        inner: Arc::new(Inner {
            device: tokio::sync::Mutex::new(Some(device)),
            peers: std::sync::Mutex::new(config.peers),
            logger,
        }),
    })
}

impl Tunnel {
    /// Reapplies UAPI configuration to the running device without restarting
    /// it (used for the screen-state keepalive toggle).
    pub fn set_config(&self, uapi_config: &str) -> Result<(), String> {
        let config = parse_uapi_config(uapi_config).map_err(|e| e.to_string())?;
        let inner = Arc::clone(&self.inner);

        self.runtime.block_on(async move {
            let guard = inner.device.lock().await;
            let device = guard.as_ref().ok_or("tunnel stopped")?;

            let new_keys: Vec<PublicKey> = config.peers.iter().map(|p| p.public_key).collect();
            device
                .write(async |d| {
                    d.set_private_key(config.private_key.clone()).await;
                    // Drop peers absent from the new config, then upsert the rest.
                    let existing: Vec<PublicKey> =
                        d.peers().await.into_iter().map(|p| p.peer.public_key).collect();
                    for stale in existing.iter().filter(|k| !new_keys.contains(k)) {
                        d.remove_peer(stale).await;
                    }
                    for peer in &config.peers {
                        d.add_or_update_peer(peer.to_peer()).await;
                    }
                })
                .await
                .map_err(|e| e.to_string())?;

            *inner.peers.lock().unwrap() = config.peers;
            Ok(())
        })
    }

    /// Transfer counters and newest handshake, summed across all peers.
    /// rx_bytes counts decrypted transport payload, so it only advances when
    /// the tunnel actually carries return traffic — that is the liveness
    /// signal the connectivity monitor is biased toward.
    pub fn stats(&self) -> Result<TunnelStats, String> {
        let inner = Arc::clone(&self.inner);
        self.runtime.block_on(async move {
            let guard = inner.device.lock().await;
            let device = guard.as_ref().ok_or("tunnel stopped")?;

            let now_millis = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64;

            let mut stats = TunnelStats {
                rx_bytes: 0,
                tx_bytes: 0,
                latest_handshake_millis: 0,
            };
            for peer in device.peers().await {
                stats.rx_bytes += peer.stats.rx_bytes as i64;
                stats.tx_bytes += peer.stats.tx_bytes as i64;
                if let Some(elapsed) = peer.stats.last_handshake {
                    let ts = now_millis - elapsed.as_millis() as i64;
                    if ts > stats.latest_handshake_millis {
                        stats.latest_handshake_millis = ts;
                    }
                }
            }
            Ok(stats)
        })
    }

    /// Prods a stalled tunnel: temporarily lowers the persistent keepalive to
    /// 1s so gotatun's timer loop emits traffic (and re-handshakes if the
    /// session expired), then restores the configured interval.
    pub fn send_keepalive(&self) {
        let inner = Arc::clone(&self.inner);
        self.runtime.spawn(async move {
            let keys: Vec<PublicKey> = {
                let peers = inner.peers.lock().unwrap();
                peers.iter().map(|p| p.public_key).collect()
            };

            {
                let guard = inner.device.lock().await;
                let Some(device) = guard.as_ref() else { return };
                let result = device
                    .write(async |d| {
                        for key in &keys {
                            d.modify_peer(key, |p| p.set_keepalive(Some(PROD_KEEPALIVE_SECS)))
                                .await;
                        }
                    })
                    .await;
                if let Err(e) = result {
                    inner.logger.error(&format!("keepalive prod failed: {e}"));
                    return;
                }
            }

            tokio::time::sleep(PROD_RESTORE_AFTER).await;

            let configured: Vec<(PublicKey, Option<u16>)> = {
                let peers = inner.peers.lock().unwrap();
                peers.iter().map(|p| (p.public_key, p.keepalive)).collect()
            };
            let guard = inner.device.lock().await;
            let Some(device) = guard.as_ref() else { return };
            let _ = device
                .write(async |d| {
                    for (key, keepalive) in &configured {
                        d.modify_peer(key, |p| p.set_keepalive(*keepalive)).await;
                    }
                })
                .await;
        });
    }

    /// Newest peer handshake timestamp in epoch millis.
    pub fn latest_handshake_millis(&self) -> Result<i64, String> {
        Ok(self.stats()?.latest_handshake_millis)
    }

    /// Closes and re-binds the outer UDP sockets (re-protecting the new fds
    /// via the Java callback). Call after the default network changed so the
    /// encrypted traffic leaves via the new network instead of a dead socket.
    pub fn rebind(&self) -> Result<(), String> {
        let inner = Arc::clone(&self.inner);
        self.runtime.block_on(async move {
            let guard = inner.device.lock().await;
            let device = guard.as_ref().ok_or("tunnel stopped")?;
            device
                .write(async |d| {
                    // Toggling the port marks the connection dirty so the
                    // write commit re-runs Connection::set_up, which re-binds
                    // through ProtectedUdpFactory. The final value is the
                    // original port again.
                    let port = d.listen_port();
                    d.set_listen_port(if port == 0 { 1 } else { 0 });
                    d.set_listen_port(port);
                })
                .await
                .map_err(|e| e.to_string())?;
            inner.logger.verbose("rebound WG UDP sockets");
            Ok(())
        })
    }

    /// Moves a peer to a new endpoint (already-resolved `ip:port` string)
    /// without disturbing the session, e.g. after DNS re-resolution.
    pub fn update_endpoint(&self, public_key: &PublicKey, endpoint: &str) -> Result<(), String> {
        let addr: std::net::SocketAddr = endpoint
            .parse()
            .map_err(|e| format!("bad endpoint {endpoint}: {e}"))?;
        let inner = Arc::clone(&self.inner);

        self.runtime.block_on(async move {
            let guard = inner.device.lock().await;
            let device = guard.as_ref().ok_or("tunnel stopped")?;
            let found = device
                .write(async |d| {
                    d.modify_peer(&public_key.clone(), |p| p.set_endpoint(Some(addr)))
                        .await
                })
                .await
                .map_err(|e| e.to_string())?;
            if !found {
                return Err("no such peer".to_owned());
            }
            {
                let mut peers = inner.peers.lock().unwrap();
                if let Some(p) = peers.iter_mut().find(|p| p.public_key == *public_key) {
                    p.endpoint = Some(addr);
                }
            }
            inner.logger.verbose(&format!("peer endpoint updated to {addr}"));
            Ok(())
        })
    }

    /// Tears down gotatun and closes the duplicated fds. Idempotent.
    pub fn stop(&self) {
        let inner = Arc::clone(&self.inner);
        self.runtime.block_on(async move {
            let device = inner.device.lock().await.take();
            if let Some(device) = device {
                device.stop().await;
            }
        });
        self.inner.logger.verbose("gotatun device stopped");
    }
}

impl Drop for Tunnel {
    fn drop(&mut self) {
        self.stop();
    }
}

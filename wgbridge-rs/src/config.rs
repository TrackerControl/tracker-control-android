//! Parses the UAPI-style text config produced by WgConfig.toUapi() on the
//! Java side into gotatun's typed configuration. Endpoints arrive already
//! resolved to IP literals (WgEgress resolves hostnames before building the
//! UAPI text).

use std::fmt;
use std::net::SocketAddr;

use gotatun::device::Peer;
use gotatun::x25519::{PublicKey, StaticSecret};
use ipnetwork::IpNetwork;

#[derive(Debug)]
pub struct ConfigError(pub String);

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "config: {}", self.0)
    }
}

impl std::error::Error for ConfigError {}

fn err(msg: impl Into<String>) -> ConfigError {
    ConfigError(msg.into())
}

/// Device configuration parsed from UAPI text.
pub struct DeviceConfig {
    pub private_key: StaticSecret,
    pub peers: Vec<PeerConfig>,
}

/// One peer's configuration. Kept separate from [`gotatun::device::Peer`] so
/// the keepalive prod logic can consult the configured interval later.
#[derive(Clone)]
pub struct PeerConfig {
    pub public_key: PublicKey,
    pub preshared_key: Option<[u8; 32]>,
    pub endpoint: Option<SocketAddr>,
    pub keepalive: Option<u16>,
    pub allowed_ips: Vec<IpNetwork>,
}

impl PeerConfig {
    pub fn to_peer(&self) -> Peer {
        let mut peer = Peer::new(self.public_key)
            .with_allowed_ips(self.allowed_ips.iter().copied());
        if let Some(endpoint) = self.endpoint {
            peer = peer.with_endpoint(endpoint);
        }
        if let Some(psk) = self.preshared_key {
            peer = peer.with_preshared_key(psk);
        }
        peer.keepalive = self.keepalive;
        peer
    }
}

fn parse_key_hex(value: &str) -> Result<[u8; 32], ConfigError> {
    let bytes = hex::decode(value).map_err(|e| err(format!("bad hex key: {e}")))?;
    bytes
        .try_into()
        .map_err(|_| err("key must be 32 bytes"))
}

/// Parses UAPI `key=value` lines. Unknown keys are ignored (`replace_allowed_ips`
/// is implicit: peers are always applied with full replacement).
pub fn parse_uapi_config(uapi: &str) -> Result<DeviceConfig, ConfigError> {
    let mut private_key: Option<StaticSecret> = None;
    let mut peers: Vec<PeerConfig> = Vec::new();

    for line in uapi.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            return Err(err(format!("malformed line: {line}")));
        };

        match key {
            "private_key" => {
                private_key = Some(StaticSecret::from(parse_key_hex(value)?));
            }
            "public_key" => {
                peers.push(PeerConfig {
                    public_key: PublicKey::from(parse_key_hex(value)?),
                    preshared_key: None,
                    endpoint: None,
                    keepalive: None,
                    allowed_ips: Vec::new(),
                });
            }
            "preshared_key" => {
                let peer = peers.last_mut().ok_or_else(|| err("preshared_key before public_key"))?;
                peer.preshared_key = Some(parse_key_hex(value)?);
            }
            "endpoint" => {
                let peer = peers.last_mut().ok_or_else(|| err("endpoint before public_key"))?;
                let addr: SocketAddr = value
                    .parse()
                    .map_err(|e| err(format!("bad endpoint {value}: {e}")))?;
                peer.endpoint = Some(addr);
            }
            "persistent_keepalive_interval" => {
                let peer = peers
                    .last_mut()
                    .ok_or_else(|| err("persistent_keepalive_interval before public_key"))?;
                let secs: u16 = value
                    .parse()
                    .map_err(|e| err(format!("bad keepalive {value}: {e}")))?;
                peer.keepalive = if secs == 0 { None } else { Some(secs) };
            }
            "allowed_ip" => {
                let peer = peers.last_mut().ok_or_else(|| err("allowed_ip before public_key"))?;
                let net: IpNetwork = value
                    .parse()
                    .map_err(|e| err(format!("bad allowed_ip {value}: {e}")))?;
                peer.allowed_ips.push(net);
            }
            // Emitted by WgConfig.toUapi(); replacement is our only mode.
            "replace_allowed_ips" | "replace_peers" => {}
            _ => {}
        }
    }

    let private_key = private_key.ok_or_else(|| err("missing private_key"))?;
    if peers.is_empty() {
        return Err(err("no peers configured"));
    }
    Ok(DeviceConfig { private_key, peers })
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY_HEX: &str = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a";
    const PEER_HEX: &str = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";

    fn sample_uapi() -> String {
        format!(
            "private_key={KEY_HEX}\n\
             public_key={PEER_HEX}\n\
             endpoint=203.0.113.5:51820\n\
             persistent_keepalive_interval=25\n\
             replace_allowed_ips=true\n\
             allowed_ip=0.0.0.0/0\n\
             allowed_ip=::/0\n"
        )
    }

    #[test]
    fn parses_full_config() {
        let config = parse_uapi_config(&sample_uapi()).unwrap();
        assert_eq!(config.peers.len(), 1);
        let peer = &config.peers[0];
        assert_eq!(peer.endpoint, Some("203.0.113.5:51820".parse().unwrap()));
        assert_eq!(peer.keepalive, Some(25));
        assert_eq!(peer.allowed_ips.len(), 2);
        assert_eq!(hex::encode(peer.public_key.as_bytes()), PEER_HEX);
    }

    #[test]
    fn keepalive_zero_means_disabled() {
        let uapi = sample_uapi().replace(
            "persistent_keepalive_interval=25",
            "persistent_keepalive_interval=0",
        );
        let config = parse_uapi_config(&uapi).unwrap();
        assert_eq!(config.peers[0].keepalive, None);
    }

    #[test]
    fn parses_ipv6_endpoint() {
        let uapi = sample_uapi().replace("203.0.113.5:51820", "[2001:db8::1]:51820");
        let config = parse_uapi_config(&uapi).unwrap();
        assert_eq!(
            config.peers[0].endpoint,
            Some("[2001:db8::1]:51820".parse().unwrap())
        );
    }

    #[test]
    fn rejects_missing_private_key() {
        assert!(parse_uapi_config(&format!("public_key={PEER_HEX}\n")).is_err());
    }

    #[test]
    fn rejects_peer_attributes_without_peer() {
        assert!(parse_uapi_config(&format!(
            "private_key={KEY_HEX}\nendpoint=1.2.3.4:1\n"
        ))
        .is_err());
    }
}

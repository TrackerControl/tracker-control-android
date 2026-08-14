//! X25519 key helpers matching the old Wgbridge.generatePrivateKey /
//! Wgbridge.publicKey API (base64-encoded keys, as used in wg .conf files).

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use curve25519_dalek::scalar::Scalar;
use gotatun::x25519::{PublicKey, StaticSecret};
use ring::digest::{digest, SHA512};

pub struct Ed25519WireGuardKeyPair {
    pub private_key: String,
    pub public_key_pem: String,
}

/// Returns a fresh base64 WireGuard private key.
pub fn generate_private_key() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|e| format!("getrandom: {e}"))?;
    // StaticSecret::from clamps the scalar per X25519.
    let secret = StaticSecret::from(bytes);
    Ok(BASE64.encode(secret.to_bytes()))
}

/// Derives the base64 public key for a base64 private key.
pub fn public_key(private_key_b64: &str) -> Result<String, String> {
    let bytes: [u8; 32] = BASE64
        .decode(private_key_b64.trim())
        .map_err(|e| format!("decode private key: {e}"))?
        .try_into()
        .map_err(|_| "private key must be 32 bytes".to_owned())?;
    let secret = StaticSecret::from(bytes);
    Ok(BASE64.encode(PublicKey::from(&secret).as_bytes()))
}

/// Generates the Ed25519 identity Proton registers and the matching converted
/// X25519 private key WireGuard uses. The conversion follows RFC 8032 key
/// expansion and the same scheme as Proton's go-vpn-lib `toX25519Base64`.
pub fn generate_ed25519_wireguard_key_pair() -> Result<Ed25519WireGuardKeyPair, String> {
    let mut seed = [0u8; 32];
    getrandom::fill(&mut seed).map_err(|e| format!("getrandom: {e}"))?;
    Ok(ed25519_wireguard_key_pair_from_seed(seed))
}

fn ed25519_wireguard_key_pair_from_seed(seed: [u8; 32]) -> Ed25519WireGuardKeyPair {
    let hash = digest(&SHA512, &seed);
    let mut expanded = [0u8; 32];
    expanded.copy_from_slice(&hash.as_ref()[..32]);
    expanded[0] &= 248;
    expanded[31] &= 127;
    expanded[31] |= 64;

    let scalar = Scalar::from_bytes_mod_order(expanded);
    let ed_public = (ED25519_BASEPOINT_TABLE * &scalar).compress().to_bytes();
    // SubjectPublicKeyInfo for Ed25519: SEQUENCE(AlgorithmIdentifier(1.3.101.112), BIT STRING).
    let mut spki = Vec::with_capacity(44);
    spki.extend_from_slice(&[0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00]);
    spki.extend_from_slice(&ed_public);

    Ed25519WireGuardKeyPair {
        private_key: BASE64.encode(expanded),
        public_key_pem: format!(
            "-----BEGIN PUBLIC KEY-----\n{}\n-----END PUBLIC KEY-----",
            BASE64.encode(spki)
        ),
    }
}

/// Decodes a base64 public key (used by Tunnel.updateEndpoint).
pub fn parse_public_key_b64(public_key_b64: &str) -> Result<PublicKey, String> {
    let bytes: [u8; 32] = BASE64
        .decode(public_key_b64.trim())
        .map_err(|e| format!("decode public key: {e}"))?
        .try_into()
        .map_err(|_| "public key must be 32 bytes".to_owned())?;
    Ok(PublicKey::from(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_valid_key_and_derives_public() {
        let private = generate_private_key().unwrap();
        let public = public_key(&private).unwrap();
        assert_eq!(BASE64.decode(&private).unwrap().len(), 32);
        assert_eq!(BASE64.decode(&public).unwrap().len(), 32);
        // Deterministic: same private key -> same public key.
        assert_eq!(public, public_key(&private).unwrap());
    }

    #[test]
    fn known_test_vector() {
        // RFC 7748 test vector: base64 of Alice's private/public keys.
        let private = "dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo=";
        let expected_public = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=";
        assert_eq!(public_key(private).unwrap(), expected_public);
    }

    #[test]
    fn rejects_garbage() {
        assert!(public_key("not base64!!!").is_err());
        assert!(public_key("c2hvcnQ=").is_err()); // "short"
    }

    #[test]
    fn proton_key_pair_uses_ed25519_spki_and_x25519_private_key() {
        let pair = ed25519_wireguard_key_pair_from_seed([0u8; 32]);
        assert_eq!(BASE64.decode(&pair.private_key).unwrap().len(), 32);
        let pem = pair.public_key_pem.lines().nth(1).unwrap();
        let spki = BASE64.decode(pem).unwrap();
        assert_eq!(&spki[..12], &[0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00]);
        assert_eq!(spki.len(), 44);
    }
}

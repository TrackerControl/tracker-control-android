//! X25519 key helpers matching the old Wgbridge.generatePrivateKey /
//! Wgbridge.publicKey API (base64-encoded keys, as used in wg .conf files).

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use gotatun::x25519::{PublicKey, StaticSecret};

/// Returns a fresh base64 WireGuard private key.
pub fn generate_private_key() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes).map_err(|e| format!("getrandom: {e}"))?;
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
}

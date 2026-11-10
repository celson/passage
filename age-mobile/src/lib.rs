pub mod engine;
pub mod error;
pub mod gopass;
pub mod identities;
pub mod recipients;

uniffi::setup_scaffolding!();

use crate::error::AgeError;
use crate::gopass::GopassSecret;
use crate::identities::AgeKeyPair;
use zeroize::Zeroize;

/// Generates a new random X25519 age keypair.
#[uniffi::export]
pub fn generate_keypair() -> AgeKeyPair {
    identities::generate_keypair_internal()
}

/// Derives the public recipient (`age1...`) from an age secret key (`AGE-SECRET-KEY-1...`).
#[uniffi::export]
pub fn identity_to_recipient(mut secret_identity: String) -> Result<String, AgeError> {
    let result = identities::identity_to_recipient_internal(&secret_identity);
    // The crate owns this copy once it crosses the FFI boundary; wipe it before dropping.
    secret_identity.zeroize();
    result
}

/// Encrypts raw bytes for one or more recipients (public keys).
/// If `armor` is true, the output is formatted as ASCII armor.
#[uniffi::export]
pub fn encrypt_bytes(
    plaintext: Vec<u8>,
    recipients: Vec<String>,
    armor: bool,
) -> Result<Vec<u8>, AgeError> {
    engine::encrypt_internal(&plaintext, &recipients, armor)
}

/// Decrypts ciphertext (either ASCII armored or raw binary age format).
/// Attempts decryption with the provided identities (private keys).
/// Optionally accepts a passphrase for passphrase-protected secrets.
#[uniffi::export]
pub fn decrypt_bytes(
    ciphertext: Vec<u8>,
    mut identities: Vec<String>,
    mut passphrase: Option<String>,
) -> Result<Vec<u8>, AgeError> {
    let result = engine::decrypt_internal(&ciphertext, &identities, passphrase.as_deref());
    // Wipe the copies this crate owns; the caller's own copies are out of reach here.
    identities.zeroize();
    passphrase.zeroize();
    result
}

/// Parses a `.age-recipients` or `.gopass-recipients` file content,
/// stripping comments (#), blank lines, and whitespace.
#[uniffi::export]
pub fn parse_recipients_file(content: String) -> Vec<String> {
    recipients::parse_recipients_file_internal(&content)
}

/// Parses a decrypted secret text payload into a structured GopassSecret
/// (password, attributes like login/url/otpauth, and notes).
#[uniffi::export]
pub fn parse_gopass_secret(raw: String) -> GopassSecret {
    gopass::parse_gopass_secret_internal(&raw)
}

/// Serializes a GopassSecret into the standard gopass text format.
#[uniffi::export]
pub fn serialize_gopass_secret(secret: GopassSecret) -> String {
    gopass::serialize_gopass_secret_internal(&secret)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_full_roundtrip_encryption_decryption_armored() {
        let key1 = generate_keypair();
        let key2 = generate_keypair();

        let secret_payload = "MySuperSecretPassword!\nlogin: admin@domain.com\nurl: https://admin.domain.com\n--\nPersonal backup note";

        // Encrypt for both recipients
        let recipients = vec![key1.public_recipient.clone(), key2.public_recipient.clone()];
        let encrypted_armored = encrypt_bytes(
            secret_payload.as_bytes().to_vec(),
            recipients,
            true, // ASCII armor
        )
        .expect("Encryption failed");

        let encrypted_str = String::from_utf8(encrypted_armored.clone()).expect("Valid UTF8 armored string");
        assert!(encrypted_str.starts_with("-----BEGIN AGE ENCRYPTED FILE-----"));

        // Decrypt using key1
        let decrypted1 = decrypt_bytes(encrypted_armored.clone(), vec![key1.secret_identity.clone()], None)
            .expect("Decryption with key1 failed");
        assert_eq!(String::from_utf8(decrypted1).unwrap(), secret_payload);

        // Decrypt using key2
        let decrypted2 = decrypt_bytes(encrypted_armored, vec![key2.secret_identity.clone()], None)
            .expect("Decryption with key2 failed");
        assert_eq!(String::from_utf8(decrypted2).unwrap(), secret_payload);
    }

    #[test]
    fn test_full_roundtrip_binary() {
        let key = generate_keypair();
        let payload = b"binary secret data 1234567890".to_vec();

        let encrypted = encrypt_bytes(payload.clone(), vec![key.public_recipient.clone()], false)
            .expect("Binary encryption failed");

        let decrypted = decrypt_bytes(encrypted, vec![key.secret_identity.clone()], None)
            .expect("Binary decryption failed");

        assert_eq!(decrypted, payload);
    }

    #[test]
    fn test_gopass_parser_and_serializer_roundtrip() {
        let original_text = "MasterPassword99!\nlogin: john_doe\nurl: https://github.com\n--\nMy 2FA recovery codes: 123, 456";
        let parsed = parse_gopass_secret(original_text.to_string());

        assert_eq!(parsed.password, "MasterPassword99!");
        assert_eq!(parsed.attributes.get("login").unwrap(), "john_doe");
        assert_eq!(parsed.attributes.get("url").unwrap(), "https://github.com");
        assert_eq!(parsed.notes, "My 2FA recovery codes: 123, 456");

        let serialized = serialize_gopass_secret(parsed.clone());
        let reparsed = parse_gopass_secret(serialized);

        assert_eq!(parsed, reparsed);
    }

    #[test]
    fn test_recipients_file_parsing() {
        let content = r#"
            # First key
            age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p
            
            # Empty lines below:

            # Second key
            age1u86juhyp2dhn76y4dhh29kyd20w27y0elg84v2cskzsp93j7h40s076z3z
        "#;

        let recs = parse_recipients_file(content.to_string());
        assert_eq!(recs.len(), 2);
        assert_eq!(recs[0], "age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p");
        assert_eq!(recs[1], "age1u86juhyp2dhn76y4dhh29kyd20w27y0elg84v2cskzsp93j7h40s076z3z");
    }

    #[test]
    fn test_invalid_key_error_handling() {
        let res = identity_to_recipient("not_an_age_key".to_string());
        assert!(res.is_err());
        match res.unwrap_err() {
            AgeError::InvalidIdentity { reason } => {
                assert!(reason.contains("AGE-SECRET-KEY-1"));
            }
            _ => panic!("Expected InvalidIdentity"),
        }
    }

    /// A gopass identities file is an age file protected by a passphrase (scrypt);
    /// the app opens it with `decrypt_bytes(file, [], Some(passphrase))`.
    #[test]
    fn test_passphrase_protected_identities_file() {
        use std::io::Write;

        let key = generate_keypair();
        let content = format!("# created: 2026-01-01\n# public key: {}\n{}\n", key.public_recipient, key.secret_identity);

        let passphrase = age::secrecy::SecretString::new("correct horse".to_string().into());
        let encryptor = age::Encryptor::with_user_passphrase(passphrase);
        let mut file = Vec::new();
        let mut writer = encryptor.wrap_output(&mut file).expect("wrap_output failed");
        writer.write_all(content.as_bytes()).unwrap();
        writer.finish().unwrap();

        let opened = decrypt_bytes(file.clone(), vec![], Some("correct horse".to_string()))
            .expect("opening the identities file failed");
        let text = String::from_utf8(opened).unwrap();
        assert!(text.lines().any(|l| l == key.secret_identity));

        assert!(decrypt_bytes(file.clone(), vec![], Some("wrong".to_string())).is_err());
        assert!(matches!(
            decrypt_bytes(file, vec![], None),
            Err(AgeError::PassphraseRequired)
        ));
    }

    /// 16 MiB: keep in sync with `MAX_DECRYPT_BYTES` in engine.rs.
    const LIMIT: usize = 16 * 1024 * 1024;

    #[test]
    fn test_oversized_ciphertext_is_rejected() {
        let key = generate_keypair();
        let res = decrypt_bytes(vec![0u8; LIMIT + 1], vec![key.secret_identity], None);
        match res {
            Err(AgeError::DecryptionFailed { reason }) => assert!(reason.contains("limit")),
            other => panic!("Expected DecryptionFailed, got {:?}", other),
        }
    }

    #[test]
    fn test_large_secret_below_limit_roundtrips() {
        let key = generate_keypair();
        // Leave room for the age header and per-chunk overhead.
        let payload = vec![b'a'; LIMIT - 64 * 1024];
        let encrypted = encrypt_bytes(payload.clone(), vec![key.public_recipient], false).unwrap();
        let decrypted = decrypt_bytes(encrypted, vec![key.secret_identity], None).unwrap();
        assert_eq!(decrypted, payload);
    }
}

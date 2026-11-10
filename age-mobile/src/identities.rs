use crate::error::AgeError;
use age::secrecy::ExposeSecret;
use std::str::FromStr;

#[derive(Debug, Clone, uniffi::Record)]
pub struct AgeKeyPair {
    pub public_recipient: String,
    pub secret_identity: String,
}

/// Generates a new random X25519 age keypair.
pub fn generate_keypair_internal() -> AgeKeyPair {
    let key = age::x25519::Identity::generate();
    let secret = key.to_string().expose_secret().clone();
    let public = key.to_public().to_string();

    AgeKeyPair {
        public_recipient: public,
        secret_identity: secret,
    }
}

/// Computes the public recipient (age1...) corresponding to an AGE-SECRET-KEY-1... identity.
pub fn identity_to_recipient_internal(secret: &str) -> Result<String, AgeError> {
    let trimmed = secret.trim();
    if !trimmed.starts_with("AGE-SECRET-KEY-1") {
        return Err(AgeError::InvalidIdentity {
            reason: "Age secret key must start with AGE-SECRET-KEY-1".to_string(),
        });
    }

    match age::x25519::Identity::from_str(trimmed) {
        Ok(identity) => Ok(identity.to_public().to_string()),
        Err(e) => Err(AgeError::InvalidIdentity {
            reason: format!("Failed to parse age secret key: {}", e),
        }),
    }
}

pub enum ParsedIdentity {
    X25519(age::x25519::Identity),
    Ssh(age::ssh::Identity),
}

impl ParsedIdentity {
    pub fn as_identity(&self) -> &dyn age::Identity {
        match self {
            ParsedIdentity::X25519(id) => id,
            ParsedIdentity::Ssh(id) => id,
        }
    }
}

pub fn parse_identity(
    secret: &str,
    _passphrase: Option<&str>,
) -> Result<ParsedIdentity, AgeError> {
    let trimmed = secret.trim();

    if trimmed.starts_with("AGE-SECRET-KEY-1") {
        match age::x25519::Identity::from_str(trimmed) {
            Ok(id) => Ok(ParsedIdentity::X25519(id)),
            Err(e) => Err(AgeError::InvalidIdentity {
                reason: format!("Invalid X25519 identity: {}", e),
            }),
        }
    } else if trimmed.contains("PRIVATE KEY") {
        // SSH private key PEM or OpenSSH format
        let mut cursor = std::io::Cursor::new(trimmed.as_bytes());
        // The 2nd argument of from_buffer is the key's file name (only used in prompts), not
        // a passphrase: passing the passphrase there only made an unwiped copy of it.
        match age::ssh::Identity::from_buffer(&mut cursor, None) {
            Ok(age::ssh::Identity::Unsupported(_)) => Err(AgeError::InvalidIdentity {
                reason: "Unsupported SSH private key algorithm".to_string(),
            }),
            Ok(id) => Ok(ParsedIdentity::Ssh(id)),
            Err(e) => Err(AgeError::InvalidIdentity {
                reason: format!("Failed to parse SSH identity: {:?}", e),
            }),
        }
    } else {
        // Fallback try X25519
        match age::x25519::Identity::from_str(trimmed) {
            Ok(id) => Ok(ParsedIdentity::X25519(id)),
            Err(e) => Err(AgeError::InvalidIdentity {
                reason: format!("Unknown identity format: {}", e),
            }),
        }
    }
}

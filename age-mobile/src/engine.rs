use crate::error::AgeError;
use crate::identities::{parse_identity, ParsedIdentity};
use crate::recipients::parse_recipient;
use age::secrecy::SecretString;
use std::io::{Read, Write};
use zeroize::Zeroizing;

const ARMOR_HEADER: &[u8] = b"-----BEGIN AGE ENCRYPTED FILE-----";

/// Upper bound for ciphertext and for decrypted output. Ciphertext comes from the git
/// remote, so without a cap a huge file would exhaust the phone's memory. Secrets are
/// small text files; 16 MiB is generous.
const MAX_DECRYPT_BYTES: usize = 16 * 1024 * 1024;

/// Encrypts plaintext bytes for a list of recipients (public keys).
/// If `armor` is true, output is ASCII armored (standard for text/gopass files).
pub fn encrypt_internal(
    plaintext: &[u8],
    recipients: &[String],
    armor: bool,
) -> Result<Vec<u8>, AgeError> {
    if recipients.is_empty() {
        return Err(AgeError::NoRecipients);
    }

    let mut parsed_recipients: Vec<Box<dyn age::Recipient + Send>> =
        Vec::with_capacity(recipients.len());
    for r in recipients {
        parsed_recipients.push(parse_recipient(r)?);
    }

    let encryptor =
        age::Encryptor::with_recipients(parsed_recipients).ok_or(AgeError::NoRecipients)?;

    let mut output = Vec::new();

    if armor {
        let armored_writer = age::armor::ArmoredWriter::wrap_output(
            &mut output,
            age::armor::Format::AsciiArmor,
        )
        .map_err(|e| AgeError::EncryptionFailed {
            reason: e.to_string(),
        })?;

        let mut encrypt_writer = encryptor
            .wrap_output(armored_writer)
            .map_err(|e| AgeError::EncryptionFailed {
                reason: e.to_string(),
            })?;

        encrypt_writer
            .write_all(plaintext)
            .map_err(|e| AgeError::EncryptionFailed {
                reason: e.to_string(),
            })?;

        let inner_armored = encrypt_writer
            .finish()
            .map_err(|e| AgeError::EncryptionFailed {
                reason: e.to_string(),
            })?;

        inner_armored
            .finish()
            .map_err(|e| AgeError::EncryptionFailed {
                reason: e.to_string(),
            })?;
    } else {
        let mut encrypt_writer =
            encryptor
                .wrap_output(&mut output)
                .map_err(|e| AgeError::EncryptionFailed {
                    reason: e.to_string(),
                })?;

        encrypt_writer
            .write_all(plaintext)
            .map_err(|e| AgeError::EncryptionFailed {
                reason: e.to_string(),
            })?;

        encrypt_writer
            .finish()
            .map_err(|e| AgeError::EncryptionFailed {
                reason: e.to_string(),
            })?;
    }

    Ok(output)
}

/// Decrypts ciphertext (armored or binary) using one or more identities or passphrase.
pub fn decrypt_internal(
    ciphertext: &[u8],
    identities: &[String],
    passphrase: Option<&str>,
) -> Result<Vec<u8>, AgeError> {
    if ciphertext.len() > MAX_DECRYPT_BYTES {
        return Err(AgeError::DecryptionFailed {
            reason: format!("Ciphertext exceeds the {} byte limit", MAX_DECRYPT_BYTES),
        });
    }

    let trimmed = match std::str::from_utf8(ciphertext) {
        Ok(s) => s.trim_start().as_bytes(),
        Err(_) => ciphertext,
    };

    if trimmed.starts_with(ARMOR_HEADER) {
        let reader = age::armor::ArmoredReader::new(trimmed);
        decrypt_from_reader(reader, identities, passphrase)
    } else {
        decrypt_from_reader(ciphertext, identities, passphrase)
    }
}

/// Reads at most `limit` bytes; one extra byte tells "exactly at the limit" from "over it".
/// The buffer is wiped if reading fails or the limit is exceeded.
fn read_limited<R: Read>(reader: R, limit: usize) -> Result<Vec<u8>, AgeError> {
    let mut decrypted = Zeroizing::new(Vec::new());
    reader
        .take(limit as u64 + 1)
        .read_to_end(&mut decrypted)
        .map_err(|e| AgeError::DecryptionFailed {
            reason: e.to_string(),
        })?;

    if decrypted.len() > limit {
        return Err(AgeError::DecryptionFailed {
            reason: format!("Decrypted data exceeds the {} byte limit", limit),
        });
    }

    Ok(std::mem::take(&mut *decrypted))
}

fn decrypt_from_reader<R: Read>(
    reader: R,
    identities: &[String],
    passphrase: Option<&str>,
) -> Result<Vec<u8>, AgeError> {
    let decryptor = age::Decryptor::new(reader).map_err(|e| AgeError::DecryptionFailed {
        reason: e.to_string(),
    })?;

    match decryptor {
        age::Decryptor::Recipients(d) => {
            if identities.is_empty() {
                return Err(AgeError::NoIdentities);
            }

            let mut parsed: Vec<ParsedIdentity> = Vec::with_capacity(identities.len());
            for id_str in identities {
                parsed.push(parse_identity(id_str, passphrase)?);
            }

            let id_refs: Vec<&dyn age::Identity> =
                parsed.iter().map(|p| p.as_identity()).collect();

            let decrypt_reader = d
                .decrypt(id_refs.into_iter())
                .map_err(|e| AgeError::DecryptionFailed {
                    reason: e.to_string(),
                })?;

            read_limited(decrypt_reader, MAX_DECRYPT_BYTES)
        }
        age::Decryptor::Passphrase(d) => {
            let pass = passphrase.ok_or(AgeError::PassphraseRequired)?;
            // Build the Box<str> straight from the borrowed &str: no intermediate String is
            // left behind, and SecretString wipes its own copy on drop.
            let secret_pass = SecretString::new(pass.into());

            let decrypt_reader = d
                .decrypt(&secret_pass, None)
                .map_err(|e| AgeError::DecryptionFailed {
                    reason: e.to_string(),
                })?;

            read_limited(decrypt_reader, MAX_DECRYPT_BYTES)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // The age format always adds overhead, so ciphertext can never be smaller than its
    // plaintext and the output cap cannot be reached through decrypt_internal alone;
    // it is tested on the bounded read itself.
    #[test]
    fn test_read_limited_is_inclusive_and_rejects_overflow() {
        assert_eq!(read_limited(&[7u8; 10][..], 10).unwrap().len(), 10);
        assert!(matches!(
            read_limited(&[7u8; 11][..], 10),
            Err(AgeError::DecryptionFailed { .. })
        ));
    }
}

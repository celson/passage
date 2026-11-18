package app.passage.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clean Kotlin facade wrapping the native UniFFI age engine.
 */
class AgeCryptoManager {

    /**
     * Generates a new random X25519 age keypair.
     */
    suspend fun generateKeyPair(): AgeKeyPair = withContext(Dispatchers.Default) {
        generateKeypair()
    }

    /**
     * Derives the public recipient (`age1...`) from an age secret key (`AGE-SECRET-KEY-1...`).
     */
    suspend fun deriveRecipient(secretIdentity: String): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            identityToRecipient(secretIdentity)
        }
    }

    /**
     * Criptografa o texto/segredo para os destinatários (recipients).
     *
     * O padrão é o formato binário do age, o mesmo que o gopass grava e espera ler: o backend
     * age do gopass não lê arquivos com ASCII armor (`-----BEGIN AGE ENCRYPTED FILE-----`).
     */
    suspend fun encryptSecret(
        secret: GopassSecret,
        recipients: List<String>,
        armor: Boolean = false
    ): Result<ByteArray> = withContext(Dispatchers.Default) {
        runCatching {
            val rawText = serializeGopassSecret(secret)
            val bytes = rawText.toByteArray(Charsets.UTF_8)
            encryptBytes(bytes, recipients, armor)
        }
    }

    /**
     * Descriptografa o conteúdo de um arquivo `.age` e retorna o segredo gopass estruturado.
     */
    suspend fun decryptSecret(
        encryptedBytes: ByteArray,
        identities: List<String>,
        passphrase: String? = null
    ): Result<GopassSecret> = withContext(Dispatchers.Default) {
        runCatching {
            val decryptedBytes = decryptBytes(encryptedBytes, identities, passphrase)
            val text = decryptedBytes.toString(Charsets.UTF_8)
            parseGopassSecret(text)
        }
    }

    /**
     * Extrai os destinatários de um arquivo `.age-recipients`.
     */
    fun parseRecipients(fileContent: String): List<String> {
        return parseRecipientsFile(fileContent)
    }

    /**
     * Extracts the age secret keys from a gopass identities file, which is an age file protected
     * by a passphrase, or from plain text containing `AGE-SECRET-KEY-1...` lines.
     */
    suspend fun importIdentities(content: ByteArray, passphrase: String?): Result<List<String>> =
        withContext(Dispatchers.Default) {
            runCatching {
                val text = if (looksLikeAgeFile(content)) {
                    decryptBytes(content, emptyList(), passphrase?.takeIf { it.isNotEmpty() })
                        .toString(Charsets.UTF_8)
                } else {
                    content.toString(Charsets.UTF_8)
                }
                val identities = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith(IDENTITY_PREFIX, ignoreCase = true) }
                    .map { it.uppercase() }
                    .distinct()
                    .toList()
                require(identities.isNotEmpty()) { "Nenhuma chave AGE-SECRET-KEY encontrada no conteúdo" }
                identities.forEach { identityToRecipient(it) }
                identities
            }
        }

    private fun looksLikeAgeFile(content: ByteArray): Boolean {
        val head = content.take(40).toByteArray().toString(Charsets.US_ASCII)
        return head.startsWith("age-encryption.org/") || head.startsWith("-----BEGIN AGE ENCRYPTED FILE")
    }

    private companion object {
        const val IDENTITY_PREFIX = "AGE-SECRET-KEY-"
    }
}

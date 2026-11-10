package app.passage.core.git.tree

import java.io.File
import java.util.Base64

/** A recipients file of the vault (`.age-recipients` or `.gopass-recipients`) and the keys it lists. */
data class RecipientsFile(
    val relativePath: String,
    val recipients: List<String>
)

/** Difference between a recipients file in the vault and the version the user approved. */
data class RecipientsChange(
    val file: RecipientsFile,
    val added: List<String>,
    val removed: List<String>,
    /** True when no version of this file was approved before. */
    val isNew: Boolean
)

/**
 * Recipients the user approved for each recipients file. Recipients files come from the git
 * remote, so whoever can write there could add a key of their own; secrets are only encrypted
 * for a set that matches the approved one. The file lives in app-private storage, outside the vault.
 */
class RecipientTrustStore(private val file: File) {

    @Synchronized
    fun isTrusted(recipientsFile: RecipientsFile): Boolean {
        val approved = load()[recipientsFile.relativePath] ?: return false
        return approved.isNotEmpty() && approved == recipientsFile.recipients.toSet()
    }

    /** Files in [current] whose recipients differ from the approved ones. */
    @Synchronized
    fun pendingChanges(current: List<RecipientsFile>): List<RecipientsChange> {
        val approved = load()
        return current.mapNotNull { recipientsFile ->
            val trusted = approved[recipientsFile.relativePath]
            val now = recipientsFile.recipients.toSet()
            if (trusted == now) {
                null
            } else {
                RecipientsChange(
                    file = recipientsFile,
                    added = (now - trusted.orEmpty()).toList(),
                    removed = (trusted.orEmpty() - now).toList(),
                    isNew = trusted == null
                )
            }
        }
    }

    /** Stores exactly the recipients the user reviewed, not whatever the vault holds by now. */
    @Synchronized
    fun approve(files: List<RecipientsFile>) {
        val approved = load().toMutableMap()
        files.forEach { approved[it.relativePath] = it.recipients.toSet() }
        write(approved)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    // One `base64(path) base64(recipient)` line per approved recipient
    private fun load(): Map<String, Set<String>> {
        if (!file.exists()) return emptyMap()
        val result = LinkedHashMap<String, MutableSet<String>>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 2) {
                runCatching { result.getOrPut(decode(parts[0])) { LinkedHashSet() }.add(decode(parts[1])) }
            }
        }
        return result
    }

    private fun write(approved: Map<String, Set<String>>) {
        file.parentFile?.mkdirs()
        val text = buildString {
            approved.forEach { (path, recipients) ->
                recipients.forEach { append(encode(path)).append(' ').append(encode(it)).append('\n') }
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Não foi possível gravar os destinatários aprovados" }
        }
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
}

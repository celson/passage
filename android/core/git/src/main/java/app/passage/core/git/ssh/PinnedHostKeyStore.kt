package app.passage.core.git.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Public host key of an SSH server. [host] is the name JSch checks, `[host]:port` for ports other than 22.
 */
data class HostKeyInfo(
    val host: String,
    val keyType: String,
    val keyBase64: String
) {
    /** Fingerprint in the OpenSSH layout (`SHA256:...`), the one git hosts publish. */
    val fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(keyBase64))
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

    companion object {
        fun of(host: String, keyBlob: ByteArray): HostKeyInfo =
            HostKeyInfo(host, keyTypeOf(keyBlob), Base64.getEncoder().encodeToString(keyBlob))

        /** The key type is the first SSH string of the blob. */
        private fun keyTypeOf(keyBlob: ByteArray): String = runCatching {
            val buffer = ByteBuffer.wrap(keyBlob)
            val name = ByteArray(buffer.int)
            buffer.get(name)
            name.toString(Charsets.US_ASCII)
        }.getOrDefault("unknown")
    }
}

/**
 * Host keys the user approved, one `host type base64` line each. The file lives in app-private
 * storage, never inside the vault clone, so the remote cannot change it.
 */
class PinnedHostKeyStore(private val file: File) {

    @Synchronized
    fun all(): List<HostKeyInfo> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 3) HostKeyInfo(parts[0], parts[1], parts[2]) else null
        }
    }

    fun keysFor(host: String): List<HostKeyInfo> = all().filter { it.host == host }

    @Synchronized
    fun trust(key: HostKeyInfo) {
        if (key in all()) return
        write(all() + key)
    }

    @Synchronized
    fun forget(host: String) {
        write(all().filterNot { it.host == host })
    }

    private fun write(keys: List<HostKeyInfo>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(keys.joinToString("") { "${it.host} ${it.keyType} ${it.keyBase64}\n" }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Não foi possível gravar as chaves de host confiáveis" }
        }
    }
}

/** Host key JSch refused: [changed] is true when the host is pinned to a different key. */
data class RejectedHostKey(val key: HostKeyInfo, val changed: Boolean)

/**
 * JSch host key check backed by [PinnedHostKeyStore]. It never adds keys by itself: a key that is
 * not pinned is refused and kept in [rejected], so the caller can ask the user about it.
 */
class PinningHostKeyRepository(private val store: PinnedHostKeyStore) : HostKeyRepository {

    @Volatile
    var rejected: RejectedHostKey? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val presented = HostKeyInfo.of(host, key)
        val pinned = store.keysFor(host)
        return when {
            pinned.any { it.keyBase64 == presented.keyBase64 } -> HostKeyRepository.OK
            // A pinned host showing any other key, even of another type, counts as changed
            pinned.isNotEmpty() -> {
                rejected = RejectedHostKey(presented, changed = true)
                HostKeyRepository.CHANGED
            }
            else -> {
                rejected = RejectedHostKey(presented, changed = false)
                HostKeyRepository.NOT_INCLUDED
            }
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        // Keys are only pinned after the user approves them
    }

    override fun remove(host: String, type: String?) {}

    override fun remove(host: String, type: String?, key: ByteArray?) {}

    override fun getKnownHostsRepositoryID(): String = "passage-pinned-host-keys"

    override fun getHostKey(): Array<HostKey> = toHostKeys(store.all())

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        toHostKeys(
            store.all().filter { (host == null || it.host == host) && (type == null || it.keyType == type) }
        )

    private fun toHostKeys(keys: List<HostKeyInfo>): Array<HostKey> =
        keys.mapNotNull { key ->
            runCatching { HostKey(key.host, Base64.getDecoder().decode(key.keyBase64)) }.getOrNull()
        }.toTypedArray()
}

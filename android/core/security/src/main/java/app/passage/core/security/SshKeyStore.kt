package app.passage.core.security

import android.content.Context
import java.io.File

/**
 * Keeps the device's SSH private key encrypted at rest under [VaultLock]. Every call throws
 * [VaultLockedException] while the vault is locked.
 *
 * @param legacyPlainFile where versions up to 1.0.4 stored the key unencrypted.
 */
class SshKeyStore(
    context: Context,
    private val vaultLock: VaultLock,
    private val legacyPlainFile: File
) {
    private val file = File(VaultLock.keysDir(context), FILE_NAME)

    fun load(): String? {
        vaultLock.readEncrypted(file)?.let { return it.toString(Charsets.UTF_8) }

        // Move a key left unencrypted by an earlier version under the vault key
        if (!legacyPlainFile.exists()) return null
        val pem = legacyPlainFile.readText(Charsets.UTF_8)
        save(pem)
        return pem
    }

    fun save(privateKeyPem: String) {
        vaultLock.writeEncrypted(file, privateKeyPem.toByteArray(Charsets.UTF_8))
        legacyPlainFile.delete()
    }

    private companion object {
        const val FILE_NAME = "ssh_key.enc"
    }
}

package app.passage.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Keeps the age secret keys (`AGE-SECRET-KEY-1...`) encrypted at rest under [VaultLock], so they
 * can only be read after the user authenticates. Every call throws [VaultLockedException] while
 * the vault is locked. The passphrase that protected the original identities file is never stored.
 */
class IdentityStore(context: Context, private val vaultLock: VaultLock) {

    private val appContext = context.applicationContext
    private val file = File(VaultLock.keysDir(appContext), FILE_NAME)

    fun getIdentities(): List<String> {
        val text = vaultLock.readEncrypted(file)?.toString(Charsets.UTF_8)
            ?: return migrateLegacyIdentities()
        return text.lineSequence().filter { it.isNotBlank() }.toList()
    }

    fun saveIdentities(identities: List<String>) {
        vaultLock.writeEncrypted(file, identities.joinToString("\n").toByteArray(Charsets.UTF_8))
        deleteLegacyStore()
    }

    fun clear() {
        file.delete()
        deleteLegacyStore()
    }

    /**
     * Versions up to 1.0.4 kept the keys in EncryptedSharedPreferences, whose master key needs no
     * user authentication. Moves them under the vault key and removes the old copy.
     */
    private fun migrateLegacyIdentities(): List<String> {
        if (!legacyPrefsFile().exists()) return emptyList()

        val identities = runCatching {
            EncryptedSharedPreferences.create(
                appContext,
                LEGACY_PREFS_NAME,
                MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).getString(LEGACY_KEY_IDENTITIES, null)
        }.getOrNull()
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()

        if (identities.isNotEmpty()) {
            saveIdentities(identities)
        } else {
            deleteLegacyStore()
        }
        return identities
    }

    private fun deleteLegacyStore() {
        if (legacyPrefsFile().exists()) appContext.deleteSharedPreferences(LEGACY_PREFS_NAME)
    }

    private fun legacyPrefsFile(): File =
        File(appContext.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml")

    private companion object {
        const val FILE_NAME = "identities.enc"
        const val LEGACY_PREFS_NAME = "passage_identities"
        const val LEGACY_KEY_IDENTITIES = "age_identities"
    }
}

package app.passage.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The vault is locked: the user has to authenticate before secrets can be read or written. */
class VaultLockedException : IllegalStateException("O cofre está bloqueado")

/**
 * The Keystore key was invalidated (biometrics or screen lock changed) or is gone. What it
 * protected cannot be recovered: the vault has to be reset and the keys imported again.
 */
class VaultKeyInvalidatedException(cause: Throwable? = null) :
    IllegalStateException("A chave do cofre foi invalidada", cause)

/**
 * Gate for everything the app keeps secret at rest (age identities, SSH private key).
 *
 * A random data key encrypts those files. It is itself wrapped by an Android Keystore key that
 * only works after the user authenticates for that very operation, so the data key cannot be
 * recovered without biometrics or the device credential, even by code running as the app.
 * While unlocked the data key stays in memory; [lock] wipes it.
 */
class VaultLock internal constructor(private val keysDir: File) {

    enum class State { LOCKED, UNLOCKED }

    private val wrappedKeyFile = File(keysDir, WRAPPED_KEY_FILE)

    private val _state = MutableStateFlow(State.LOCKED)
    val state: StateFlow<State> = _state.asStateFlow()

    private var dataKey: ByteArray? = null

    /** False until the first successful [unlock], which creates the data key. */
    val isInitialized: Boolean
        get() = wrappedKeyFile.exists()

    /**
     * Cipher the user must authorize, through a BiometricPrompt CryptoObject, before [unlock].
     *
     * @throws VaultKeyInvalidatedException when the existing Keystore key can no longer be used.
     */
    @Synchronized
    fun prepareCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        if (!isInitialized) {
            cipher.init(Cipher.ENCRYPT_MODE, createKeystoreKey())
            return cipher
        }

        val wrapped = wrappedKeyFile.readBytes()
        try {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: throw VaultKeyInvalidatedException()
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultKeyInvalidatedException(e)
        }
        return cipher
    }

    /** Opens the vault with a cipher from [prepareCipher] that the user has just authorized. */
    @Synchronized
    fun unlock(authorizedCipher: Cipher) {
        val key = if (!isInitialized) {
            val fresh = ByteArray(DATA_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val wrapped = authorizedCipher.iv + authorizedCipher.doFinal(fresh)
            writeAtomically(wrappedKeyFile, wrapped)
            fresh
        } else {
            val wrapped = wrappedKeyFile.readBytes()
            authorizedCipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
        }
        dataKey?.fill(0)
        dataKey = key
        _state.value = State.UNLOCKED
    }

    @Synchronized
    fun lock() {
        dataKey?.fill(0)
        dataKey = null
        _state.value = State.LOCKED
    }

    /** Encrypts [plaintext] with the data key. Output layout: IV followed by the GCM ciphertext. */
    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    @Synchronized
    fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    /** Reads a file written by [writeEncrypted]; null when it does not exist. */
    fun readEncrypted(file: File): ByteArray? = if (file.exists()) decrypt(file.readBytes()) else null

    fun writeEncrypted(file: File, plaintext: ByteArray) {
        writeAtomically(file, encrypt(plaintext))
    }

    /**
     * Destroys the Keystore key and everything encrypted under it. Used when the key was
     * invalidated: the age identities and the SSH key must be set up again afterwards.
     */
    @Synchronized
    fun reset() {
        lock()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        keysDir.deleteRecursively()
    }

    private fun sessionKey(): SecretKeySpec =
        SecretKeySpec(dataKey ?: throw VaultLockedException(), "AES")

    private fun createKeystoreKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // Authentication is required for every use of the key, bound to the operation
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Não foi possível gravar ${file.name}" }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "passage_vault_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WRAPPED_KEY_FILE = "vault.key"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val DATA_KEY_BYTES = 32

        @Volatile
        private var INSTANCE: VaultLock? = null

        /** Directory of the files protected by the vault key; never included in backups. */
        fun keysDir(context: Context): File = File(context.applicationContext.noBackupFilesDir, "keys")

        /** One lock per process: the app UI and the autofill service share the unlocked state. */
        fun getInstance(context: Context): VaultLock {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VaultLock(keysDir(context)).also { INSTANCE = it }
            }
        }
    }
}

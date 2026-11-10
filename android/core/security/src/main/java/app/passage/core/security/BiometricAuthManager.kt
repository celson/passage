package app.passage.core.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

sealed class BiometricResult {
    /** The user authenticated and [cipher] is now authorized for one operation. */
    data class Success(val cipher: Cipher) : BiometricResult()
    data class Error(val errorCode: Int, val errString: String) : BiometricResult()
    data object Unavailable : BiometricResult()
}

class BiometricAuthManager(private val context: Context) {

    // A CryptoObject can only be authorized by the device credential (PIN, pattern, password) from
    // Android 11 on; older versions need a strong biometric
    private val authenticators: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    fun canAuthenticate(): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Asks the user to authenticate and, on success, returns [cipher] authorized by the Keystore.
     * The result is bound to the cipher: there is nothing to bypass by faking a callback.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String = "Desbloquear o Passage",
        subtitle: String = "Autentique para abrir o cofre"
    ): BiometricResult = suspendCancellableCoroutine { continuation ->
        if (!canAuthenticate()) {
            continuation.resume(BiometricResult.Unavailable)
            return@suspendCancellableCoroutine
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .apply {
                if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                    setNegativeButtonText("Cancelar")
                }
            }
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!continuation.isActive) return
                    val authorized = result.cryptoObject?.cipher
                    if (authorized != null) {
                        continuation.resume(BiometricResult.Success(authorized))
                    } else {
                        continuation.resume(BiometricResult.Error(0, "Autenticação sem vínculo com a chave do cofre"))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        continuation.resume(BiometricResult.Error(errorCode, errString.toString()))
                    }
                }

                // onAuthenticationFailed is a rejected attempt: the prompt stays open for another try
            }
        )

        continuation.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}

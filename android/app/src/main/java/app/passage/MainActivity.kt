package app.passage

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.passage.core.security.BiometricAuthManager
import app.passage.core.security.BiometricResult
import app.passage.core.security.VaultKeyInvalidatedException
import app.passage.core.security.VaultLock
import app.passage.navigation.AppNavHost
import app.passage.ui.LockScreen
import kotlinx.coroutines.launch

/**
 * Owns the ViewModels of one unlocked session. Survives rotation like any ViewModel, and is
 * emptied when the vault locks so decrypted secrets do not stay in memory behind the lock screen.
 */
class UnlockedSession : ViewModel(), ViewModelStoreOwner {
    override var viewModelStore = ViewModelStore()
        private set

    fun end() {
        viewModelStore.clear()
        viewModelStore = ViewModelStore()
    }

    override fun onCleared() {
        viewModelStore.clear()
    }
}

class MainActivity : FragmentActivity() {

    private val vaultLock by lazy { VaultLock.getInstance(this) }
    private val biometricAuth by lazy { BiometricAuthManager(this) }
    private val session: UnlockedSession by viewModels()

    private val handler = Handler(Looper.getMainLooper())
    private val autoLock = Runnable { vaultLock.lock() }

    private var lockMessage by mutableStateOf<String?>(null)
    private var keyInvalidated by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Protect against screenshots, screen recordings and task switcher preview leaks
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            val lockState by vaultLock.state.collectAsState()

            MaterialTheme(colorScheme = colorScheme) {
                if (lockState == VaultLock.State.UNLOCKED) {
                    DisposableEffect(Unit) {
                        onDispose { if (!isChangingConfigurations) session.end() }
                    }
                    CompositionLocalProvider(LocalViewModelStoreOwner provides session) {
                        AppNavHost()
                    }
                } else {
                    LockScreen(
                        firstUse = !vaultLock.isInitialized,
                        message = lockMessage,
                        keyInvalidated = keyInvalidated,
                        onUnlock = ::requestUnlock,
                        onReset = ::resetVaultKey
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(autoLock)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) handler.postDelayed(autoLock, AUTO_LOCK_DELAY_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            handler.removeCallbacks(autoLock)
            vaultLock.lock()
        }
    }

    private fun requestUnlock() {
        lifecycleScope.launch {
            val cipher = try {
                vaultLock.prepareCipher()
            } catch (e: VaultKeyInvalidatedException) {
                keyInvalidated = true
                lockMessage = "A biometria ou o bloqueio de tela do aparelho mudou e a chave do cofre foi invalidada. " +
                    "Redefina o cofre e importe sua chave age novamente."
                return@launch
            } catch (e: Exception) {
                lockMessage = UNAVAILABLE_MESSAGE
                return@launch
            }

            when (val result = biometricAuth.authenticate(this@MainActivity, cipher)) {
                is BiometricResult.Success ->
                    runCatching { vaultLock.unlock(result.cipher) }
                        .onSuccess { lockMessage = null }
                        .onFailure { lockMessage = "Não foi possível abrir o cofre. Tente novamente." }
                is BiometricResult.Error -> lockMessage = result.errString
                BiometricResult.Unavailable -> lockMessage = UNAVAILABLE_MESSAGE
            }
        }
    }

    private fun resetVaultKey() {
        vaultLock.reset()
        keyInvalidated = false
        lockMessage = null
    }

    private companion object {
        /** Time in background after which the vault locks itself. */
        const val AUTO_LOCK_DELAY_MS = 60_000L

        const val UNAVAILABLE_MESSAGE =
            "O Passage exige bloqueio de tela seguro. Configure biometria (ou PIN, padrão ou senha no Android 11+) " +
                "nas configurações do aparelho."
    }
}

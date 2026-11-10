package app.passage.feature.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passage.core.git.model.ConflictStrategy
import app.passage.core.git.model.GitAuth
import app.passage.core.git.model.GitException
import app.passage.core.git.model.GitRepoStatus
import app.passage.core.git.model.SyncResult
import app.passage.core.git.ssh.GeneratedSshKeyPair
import app.passage.core.git.ssh.HostKeyInfo
import app.passage.core.git.ssh.PinnedHostKeyStore
import app.passage.core.git.ssh.SshKeyManager
import app.passage.core.git.sync.GitSyncEngine
import app.passage.core.git.tree.GopassTreeWalker
import app.passage.core.git.tree.RecipientTrustStore
import app.passage.core.git.tree.RecipientsChange
import app.passage.core.security.IdentityStore
import app.passage.core.security.SecureClipboardManager
import app.passage.core.security.SshKeyStore
import app.passage.crypto.AgeCryptoManager
import app.passage.crypto.ageUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class SyncUiOperation {
    data object Idle : SyncUiOperation()
    data class Cloning(val task: String, val percent: Float) : SyncUiOperation()
    data object Pulling : SyncUiOperation()
    data object Pushing : SyncUiOperation()
    data class Success(val message: String) : SyncUiOperation()
    data class Error(val message: String) : SyncUiOperation()
    data class Conflict(val conflictedFiles: List<String>) : SyncUiOperation()
}

/** SSH host key waiting for the user's decision; [changed] means the host was pinned to another key. */
data class HostKeyPrompt(val key: HostKeyInfo, val changed: Boolean)

class SyncViewModel(
    private val gitSyncEngine: GitSyncEngine,
    private val clipboardManager: SecureClipboardManager,
    private val cryptoManager: AgeCryptoManager,
    private val identityStore: IdentityStore,
    private val sshKeyStore: SshKeyStore,
    private val hostKeyStore: PinnedHostKeyStore,
    private val treeWalker: GopassTreeWalker,
    private val recipientTrust: RecipientTrustStore,
    private val vaultDir: File
) : ViewModel() {

    val remoteUrl = MutableStateFlow("")
    val sshPublicKey = MutableStateFlow("")
    val identityCount = MutableStateFlow(identityStore.getIdentities().size)
    private var cachedKeyPair: GeneratedSshKeyPair? = null

    private val _operation = MutableStateFlow<SyncUiOperation>(SyncUiOperation.Idle)
    val operation: StateFlow<SyncUiOperation> = _operation.asStateFlow()

    private val _repoStatus = MutableStateFlow<GitRepoStatus?>(null)
    val repoStatus: StateFlow<GitRepoStatus?> = _repoStatus.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    /** Operation refused because of the host key, repeated once the user trusts it. */
    private var retryAfterHostKey: (() -> Unit)? = null

    private val _pendingRecipients = MutableStateFlow<List<RecipientsChange>>(emptyList())
    val pendingRecipients: StateFlow<List<RecipientsChange>> = _pendingRecipients.asStateFlow()

    val isCloned: Boolean
        get() = File(vaultDir, ".git").exists()

    init {
        loadOrCreateSshKey()
        refreshStatus()
        refreshPendingRecipients()
    }

    fun loadOrCreateSshKey() {
        // Keys JSch cannot parse (saved by versions before 1.0.2) are replaced by a fresh one
        val stored = sshKeyStore.load()?.let { SshKeyManager.loadKeyPair(it) }
        if (stored != null) {
            cachedKeyPair = stored
            sshPublicKey.value = stored.publicKeyOpenSsh
        } else {
            generateNewSshKey()
        }
    }

    fun generateNewSshKey() {
        val newKey = SshKeyManager.generateKeyPair()
        cachedKeyPair = newKey
        sshPublicKey.value = newKey.publicKeyOpenSsh
        sshKeyStore.save(newKey.privateKeyPem)
    }

    fun importIdentityFile(content: ByteArray, passphrase: String) = importIdentities(content, passphrase)

    fun importIdentityText(text: String) = importIdentities(text.toByteArray(Charsets.UTF_8), null)

    fun clearIdentities() {
        identityStore.clear()
        identityCount.value = 0
    }

    private fun importIdentities(content: ByteArray, passphrase: String?) {
        viewModelScope.launch {
            cryptoManager.importIdentities(content, passphrase).fold(
                onSuccess = { identities ->
                    identityStore.saveIdentities(identities)
                    identityCount.value = identities.size
                    _operation.value = SyncUiOperation.Success("${identities.size} chave(s) age importada(s)")
                },
                onFailure = { _operation.value = SyncUiOperation.Error(it.ageUserMessage()) }
            )
        }
    }

    fun copySshPublicKey() {
        if (sshPublicKey.value.isNotEmpty()) {
            clipboardManager.copySensitiveText("Chave Pública SSH", sshPublicKey.value, autoClearSeconds = 120)
        }
    }

    fun refreshStatus() {
        if (!isCloned) return
        viewModelScope.launch {
            gitSyncEngine.getStatus(vaultDir).onSuccess {
                _repoStatus.value = it
            }
        }
    }

    fun cloneVault(url: String, onSuccess: () -> Unit) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            _operation.value = SyncUiOperation.Error("A URL do repositório Git é obrigatória")
            return
        }
        if (!CloneUrlValidator.isAllowed(trimmedUrl)) {
            _operation.value = SyncUiOperation.Error(CloneUrlValidator.ERROR_MESSAGE)
            return
        }

        viewModelScope.launch {
            _operation.value = SyncUiOperation.Cloning("Iniciando clone...", 0f)
            val auth = getAuth()

            gitSyncEngine.clone(
                remoteUrl = trimmedUrl,
                destinationDir = vaultDir,
                auth = auth,
                onProgress = { task, percent ->
                    _operation.value = SyncUiOperation.Cloning(task, percent)
                }
            ).fold(
                onSuccess = {
                    _operation.value = SyncUiOperation.Success("Repositório clonado com sucesso!")
                    refreshStatus()
                    refreshPendingRecipients()
                    onSuccess()
                },
                onFailure = { error ->
                    if (!askAboutHostKey(error) { cloneVault(url, onSuccess) }) {
                        _operation.value = SyncUiOperation.Error(error.message ?: "Erro ao clonar repositório")
                    }
                }
            )
        }
    }

    fun pull() {
        if (!isCloned) return
        viewModelScope.launch {
            _operation.value = SyncUiOperation.Pulling
            val result = gitSyncEngine.pull(vaultDir, getAuth())
            handleSyncResult(result) { pull() }
        }
    }

    fun push() {
        if (!isCloned) return
        viewModelScope.launch {
            _operation.value = SyncUiOperation.Pushing
            val result = gitSyncEngine.commitAndPush(
                repoDir = vaultDir,
                message = "Sync from Passage Android",
                auth = getAuth()
            )
            handleSyncResult(result) { push() }
        }
    }

    fun resolveConflict(relativePath: String, strategy: ConflictStrategy) {
        viewModelScope.launch {
            gitSyncEngine.resolveConflict(vaultDir, relativePath, strategy).onSuccess {
                refreshStatus()
                refreshPendingRecipients()
                _operation.value = SyncUiOperation.Success("Conflito em $relativePath resolvido")
            }
        }
    }

    private fun handleSyncResult(result: SyncResult, retry: () -> Unit) {
        when (result) {
            is SyncResult.Success -> {
                _operation.value = SyncUiOperation.Success(result.message)
                refreshStatus()
                refreshPendingRecipients()
            }
            is SyncResult.HasConflicts -> {
                _operation.value = SyncUiOperation.Conflict(result.conflictedFiles)
                refreshStatus()
                refreshPendingRecipients()
            }
            is SyncResult.Failure -> {
                if (!askAboutHostKey(result.error, retry)) {
                    _operation.value = SyncUiOperation.Error(result.error.message ?: "Falha na sincronização")
                }
            }
        }
    }

    /** Returns true when [error] is a host key the user has to decide about. */
    private fun askAboutHostKey(error: Throwable, retry: () -> Unit): Boolean {
        val prompt = when (error) {
            is GitException.UnknownHostKey -> HostKeyPrompt(error.key, changed = false)
            is GitException.HostKeyChanged -> HostKeyPrompt(error.key, changed = true)
            else -> return false
        }
        retryAfterHostKey = retry
        _hostKeyPrompt.value = prompt
        _operation.value = SyncUiOperation.Idle
        return true
    }

    /** The user compared the fingerprint and accepts this server: pin its key and repeat the operation. */
    fun trustHostKey() {
        val prompt = _hostKeyPrompt.value ?: return
        if (prompt.changed) return
        hostKeyStore.trust(prompt.key)
        _hostKeyPrompt.value = null
        retryAfterHostKey?.invoke()
        retryAfterHostKey = null
    }

    /**
     * Drops the key pinned for a host whose key changed. The next connection shows the new
     * fingerprint for approval, like a server seen for the first time.
     */
    fun forgetChangedHostKey() {
        val prompt = _hostKeyPrompt.value ?: return
        hostKeyStore.forget(prompt.key.host)
        dismissHostKeyPrompt()
        _operation.value = SyncUiOperation.Success("Chave antiga de ${prompt.key.host} removida. Sincronize de novo para conferir a nova.")
    }

    fun dismissHostKeyPrompt() {
        _hostKeyPrompt.value = null
        retryAfterHostKey = null
    }

    fun refreshPendingRecipients() {
        viewModelScope.launch {
            _pendingRecipients.value = withContext(Dispatchers.IO) {
                recipientTrust.pendingChanges(treeWalker.listRecipientsFiles(vaultDir))
            }
        }
    }

    /** Approves exactly the recipients shown to the user. */
    fun approveRecipients(reviewed: List<RecipientsChange>) {
        recipientTrust.approve(reviewed.map { it.file })
        refreshPendingRecipients()
    }

    private fun getAuth(): GitAuth {
        val pem = cachedKeyPair?.privateKeyPem ?: sshKeyStore.load().orEmpty()
        return if (pem.isNotEmpty()) {
            GitAuth.SshKey(privateKeyContent = pem)
        } else {
            GitAuth.None
        }
    }
}

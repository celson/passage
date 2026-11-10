package app.passage.di

import app.passage.core.database.PassageDatabase
import app.passage.core.domain.model.SecretDetail
import app.passage.core.domain.model.SecretItem
import app.passage.core.domain.model.VaultFolder
import app.passage.core.domain.repository.SecretRepository
import app.passage.core.domain.usecase.DeleteSecretUseCase
import app.passage.core.domain.usecase.GetSecretDetailUseCase
import app.passage.core.domain.usecase.GetVaultTreeUseCase
import app.passage.core.domain.usecase.SaveSecretUseCase
import app.passage.core.git.ssh.PinnedHostKeyStore
import app.passage.core.git.sync.GitSyncEngine
import app.passage.core.git.tree.GopassTreeWalker
import app.passage.core.git.tree.RecipientTrustStore
import app.passage.core.git.tree.VaultItem
import app.passage.core.security.BiometricAuthManager
import app.passage.core.security.IdentityStore
import app.passage.core.security.SecureClipboardManager
import app.passage.core.security.SshKeyStore
import app.passage.core.security.VaultLock
import app.passage.crypto.AgeCryptoManager
import app.passage.crypto.ageUserMessage
import app.passage.crypto.GopassSecret
import app.passage.feature.detail.ui.SecretDetailViewModel
import app.passage.feature.editor.ui.SecretEditorViewModel
import app.passage.feature.sync.ui.SyncViewModel
import app.passage.feature.vault.ui.VaultViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

val appModule = module {
    // Core Managers
    single { AgeCryptoManager() }
    // Trust decisions live in app-private storage, outside the vault clone the remote controls
    single { PinnedHostKeyStore(File(get<android.content.Context>().filesDir, "trust/known_hosts")) }
    single { RecipientTrustStore(File(get<android.content.Context>().filesDir, "trust/recipients")) }
    single { GitSyncEngine(get()) }
    single { GopassTreeWalker() }
    single { VaultLock.getInstance(get()) }
    single { IdentityStore(get(), get()) }
    single {
        val context = get<android.content.Context>()
        SshKeyStore(context, get(), legacyPlainFile = File(context.filesDir, "ssh/id_ed25519"))
    }
    single { SecureClipboardManager(get()) }
    single { BiometricAuthManager(get()) }

    // Database
    single { PassageDatabase.getInstance(get()) }
    single { get<PassageDatabase>().secretDao() }

    // Repositories
    single<SecretRepository> {
        val repoDir = File(get<android.content.Context>().filesDir, "vault")
        object : SecretRepository {
            private val walker = get<GopassTreeWalker>()
            private val crypto = get<AgeCryptoManager>()
            private val identityStore = get<IdentityStore>()
            private val recipientTrust = get<RecipientTrustStore>()

            override fun getVaultTree(): Flow<VaultFolder> = flow {
                val rootItem = walker.buildTree(repoDir)
                emit(mapToVaultFolder(rootItem))
            }

            override suspend fun getSecretDetail(relativePath: String): Result<SecretDetail> = runCatching {
                val file = File(repoDir, relativePath)
                val encryptedBytes = file.readBytes()
                val gopassSecret = crypto.decryptSecret(encryptedBytes, identityStore.getIdentities())
                    .getOrElse { throw IllegalStateException(it.ageUserMessage(), it) }

                SecretDetail(
                    name = file.name.removeSuffix(".age"),
                    relativePath = relativePath,
                    password = gopassSecret.password,
                    attributes = gopassSecret.orderedAttributes(),
                    notes = gopassSecret.notes
                )
            }

            override suspend fun saveSecret(
                relativePath: String,
                secret: SecretDetail,
                recipientsOverride: List<String>?
            ): Result<Unit> = runCatching {
                val recipients = recipientsOverride ?: trustedRecipientsFor(relativePath)
                val gopassSecret = GopassSecret(secret.password, secret.attributes, secret.attributes.keys.toList(), secret.notes)
                val encrypted = crypto.encryptSecret(gopassSecret, recipients).getOrThrow()

                val file = File(repoDir, relativePath)
                file.parentFile?.mkdirs()
                file.writeBytes(encrypted)
            }

            /** Recipients files come from the remote: only a set the user approved is used to encrypt. */
            private fun trustedRecipientsFor(relativePath: String): List<String> {
                val recipientsFile = walker.findRecipientsFileForPath(repoDir, relativePath) ?: return emptyList()
                check(recipientTrust.isTrusted(recipientsFile)) {
                    "Os destinatários em ${recipientsFile.relativePath} ainda não foram aprovados neste aparelho. " +
                        "Revise-os em Sincronizar antes de salvar."
                }
                return recipientsFile.recipients
            }

            override suspend fun deleteSecret(relativePath: String): Result<Unit> = runCatching {
                val file = File(repoDir, relativePath)
                if (file.exists()) file.delete()
            }

            override suspend fun searchSecrets(query: String): List<SecretItem> {
                return walker.listAllSecrets(repoDir)
                    .filter { it.name.contains(query, ignoreCase = true) || it.relativePath.contains(query, ignoreCase = true) }
                    .map { SecretItem(it.name, it.relativePath, it.parentFolder, it.lastModifiedMs) }
            }

            private fun mapToVaultFolder(folderItem: VaultItem.Folder): VaultFolder {
                val subfolders = folderItem.children.filterIsInstance<VaultItem.Folder>().map { mapToVaultFolder(it) }
                val secrets = folderItem.children.filterIsInstance<VaultItem.SecretFile>().map {
                    SecretItem(it.name, it.relativePath, folderItem.relativePath, it.lastModifiedMs)
                }
                return VaultFolder(folderItem.name, folderItem.relativePath, subfolders, secrets)
            }
        }
    }

    // Use Cases
    single { GetVaultTreeUseCase(get()) }
    single { GetSecretDetailUseCase(get()) }
    single { SaveSecretUseCase(get()) }
    single { DeleteSecretUseCase(get()) }

    // ViewModels
    viewModel { VaultViewModel(get()) }
    viewModel { SecretDetailViewModel(get(), get(), get()) }
    viewModel { SecretEditorViewModel(get(), get()) }
    viewModel {
        val context = get<android.content.Context>()
        SyncViewModel(
            gitSyncEngine = get(),
            clipboardManager = get(),
            cryptoManager = get(),
            identityStore = get(),
            sshKeyStore = get(),
            hostKeyStore = get(),
            treeWalker = get(),
            recipientTrust = get(),
            vaultDir = File(context.filesDir, "vault")
        )
    }
}

/** Attributes in the order they appear in the file (the engine returns them as an unordered map). */
private fun GopassSecret.orderedAttributes(): Map<String, String> {
    val ordered = LinkedHashMap<String, String>()
    attributeOrder.forEach { key -> attributes[key]?.let { ordered[key] = it } }
    attributes.forEach { (key, value) -> ordered.putIfAbsent(key, value) }
    return ordered
}

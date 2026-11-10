package app.passage.feature.sync.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.passage.core.git.model.ConflictStrategy
import app.passage.core.git.tree.RecipientsChange
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBackClick: () -> Unit,
    onCloneSuccess: () -> Unit,
    viewModel: SyncViewModel
) {
    val operation by viewModel.operation.collectAsState()
    val repoStatus by viewModel.repoStatus.collectAsState()
    val sshPublicKey by viewModel.sshPublicKey.collectAsState()
    val remoteUrl by viewModel.remoteUrl.collectAsState()
    val identityCount by viewModel.identityCount.collectAsState()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsState()
    val pendingRecipients by viewModel.pendingRecipients.collectAsState()

    val context = LocalContext.current
    var identityFile by remember { mutableStateOf<ByteArray?>(null) }
    var identityFileName by remember { mutableStateOf("") }
    var identityPassphrase by remember { mutableStateOf("") }
    var pastedKey by remember { mutableStateOf("") }
    val identityPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            identityFile = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            identityFileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(operation) {
        when (val op = operation) {
            is SyncUiOperation.Success -> snackbarHostState.showSnackbar(op.message)
            is SyncUiOperation.Error -> snackbarHostState.showSnackbar(op.message)
            else -> {}
        }
    }

    hostKeyPrompt?.let { prompt ->
        HostKeyDialog(
            prompt = prompt,
            onTrust = { viewModel.trustHostKey() },
            onForget = { viewModel.forgetChangedHostKey() },
            onDismiss = { viewModel.dismissHostKeyPrompt() }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Sincronização Git") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar Status")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Section 1: SSH Key Management
            Text(
                text = "Chave SSH do Dispositivo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Cadastre esta chave pública no seu GitHub/GitLab com permissão de escrita para sincronizar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = sshPublicKey,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                viewModel.copySshPublicKey()
                                scope.launch {
                                    snackbarHostState.showSnackbar("Chave SSH copiada para a área de transferência!")
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copiar Chave Pública")
                        }

                        TextButton(onClick = { viewModel.generateNewSshKey() }) {
                            Text("Regerar")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 2: age identities (private keys used to decrypt secrets)
            Text(
                text = "Chave age (identidades)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (identityCount > 0) {
                    "$identityCount chave(s) importada(s) e guardada(s) com criptografia no Keystore do Android."
                } else {
                    "Nenhuma chave importada: sem ela não é possível abrir os segredos. " +
                        "Importe o arquivo de identidades do gopass (e a senha dele) ou cole uma chave AGE-SECRET-KEY-1..."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedButton(
                        onClick = { identityPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (identityFile == null) "Escolher arquivo de identidades" else "Arquivo: $identityFileName")
                    }
                    if (identityFile != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = identityPassphrase,
                            onValueChange = { identityPassphrase = it },
                            label = { Text("Senha do arquivo") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                identityFile?.let { viewModel.importIdentityFile(it, identityPassphrase) }
                                identityPassphrase = ""
                                identityFile = null
                                identityFileName = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importar identidades")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = pastedKey,
                        onValueChange = { pastedKey = it },
                        label = { Text("Ou cole a chave (AGE-SECRET-KEY-1...)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                viewModel.importIdentityText(pastedKey)
                                pastedKey = ""
                            },
                            enabled = pastedKey.isNotBlank()
                        ) {
                            Text("Importar chave")
                        }
                        if (identityCount > 0) {
                            TextButton(onClick = { viewModel.clearIdentities() }) {
                                Text("Remover chaves")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 3: Remote Repository Setup / Status
            Text(
                text = "Repositório Git",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!viewModel.isCloned) {
                // Not cloned yet: Setup & Clone Wizard
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Clonar Vault Existente",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = remoteUrl,
                            onValueChange = { viewModel.remoteUrl.value = it },
                            label = { Text("URL SSH do Repositório") },
                            placeholder = { Text("git@github.com:seu-usuario/senhas.git") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        if (operation is SyncUiOperation.Cloning) {
                            val cloneOp = operation as SyncUiOperation.Cloning
                            Text(
                                text = cloneOp.task,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { cloneOp.percent },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Button(
                                onClick = { viewModel.cloneVault(remoteUrl, onCloneSuccess) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = remoteUrl.isNotBlank()
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Clonar Repositório")
                            }
                        }
                    }
                }
            } else {
                // Already cloned: Status & Pull/Push Dashboard
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Branch: ${repoStatus?.currentBranch ?: "main"}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (repoStatus?.isClean == true) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.extraSmall
                                ) {
                                    Text(
                                        text = "Limpo",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${repoStatus?.aheadCount ?: 0}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("Commits à enviar (ahead)", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${repoStatus?.behindCount ?: 0}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("Atualizações (behind)", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { viewModel.pull() },
                                modifier = Modifier.weight(1f),
                                enabled = operation !is SyncUiOperation.Pulling && operation !is SyncUiOperation.Pushing
                            ) {
                                if (operation is SyncUiOperation.Pulling) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pull")
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            FilledTonalButton(
                                onClick = { viewModel.push() },
                                modifier = Modifier.weight(1f),
                                enabled = operation !is SyncUiOperation.Pulling && operation !is SyncUiOperation.Pushing
                            ) {
                                if (operation is SyncUiOperation.Pushing) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                } else {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Push")
                                }
                            }
                        }
                    }
                }
            }

            if (pendingRecipients.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                PendingRecipientsCard(
                    changes = pendingRecipients,
                    onApprove = { viewModel.approveRecipients(pendingRecipients) }
                )
            }

            // Section 3: Merge Conflicts Handler
            if (operation is SyncUiOperation.Conflict) {
                val conflictOp = operation as SyncUiOperation.Conflict
                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Conflito de Sincronização Detectado",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Os seguintes arquivos foram modificados remotamente e localmente:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        conflictOp.conflictedFiles.forEach { filePath ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(text = filePath, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(onClick = { viewModel.resolveConflict(filePath, ConflictStrategy.THEIRS) }) {
                                            Text("Aceitar Remoto")
                                        }
                                        OutlinedButton(onClick = { viewModel.resolveConflict(filePath, ConflictStrategy.OURS) }) {
                                            Text("Manter Local")
                                        }
                                        OutlinedButton(onClick = { viewModel.resolveConflict(filePath, ConflictStrategy.KEEP_BOTH) }) {
                                            Text("Manter Ambos")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Asks the user about an SSH server that is not pinned. A changed key is never accepted in one
 * step: the old pin has to be dropped first, then the new key goes through the regular approval.
 */
@Composable
private fun HostKeyDialog(
    prompt: HostKeyPrompt,
    onTrust: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (prompt.changed) Icons.Default.Warning else Icons.Default.Key,
                contentDescription = null,
                tint = if (prompt.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(if (prompt.changed) "A chave do servidor mudou" else "Confiar neste servidor?") },
        text = {
            Column {
                Text(
                    text = if (prompt.changed) {
                        "${prompt.key.host} apresentou uma chave diferente da que você aprovou. " +
                            "Isso pode ser um ataque de interceptação. A conexão foi recusada."
                    } else {
                        "Primeira conexão com ${prompt.key.host}. Compare a impressão digital abaixo com a " +
                            "publicada pelo seu provedor Git antes de confiar."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = prompt.key.keyType, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = prompt.key.fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            if (prompt.changed) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            } else {
                TextButton(onClick = onTrust) { Text("Confiar") }
            }
        },
        dismissButton = {
            if (prompt.changed) {
                TextButton(onClick = onForget) {
                    Text("Esquecer chave antiga", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

/** Recipients files that differ from what was approved on this device; saving under them is blocked. */
@Composable
private fun PendingRecipientsCard(
    changes: List<RecipientsChange>,
    onApprove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Destinatários aguardando aprovação",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Cada chave abaixo poderá ler os segredos que você salvar. Aprove somente chaves que " +
                    "você reconhece; até lá, salvar nessas pastas fica bloqueado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            changes.forEach { change ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = change.file.relativePath,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (change.isNew) "Ainda não aprovado neste aparelho" else "Alterado desde a última aprovação",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        change.added.forEach { recipient ->
                            Text(
                                text = "+ $recipient",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        change.removed.forEach { recipient ->
                            Text(
                                text = "− $recipient",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onApprove, modifier = Modifier.fillMaxWidth()) {
                Text("Aprovar destinatários")
            }
        }
    }
}

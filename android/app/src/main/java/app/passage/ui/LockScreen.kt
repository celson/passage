package app.passage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown instead of the app while the vault is locked. Nothing decrypted is composed behind it.
 */
@Composable
fun LockScreen(
    firstUse: Boolean,
    message: String?,
    keyInvalidated: Boolean,
    onUnlock: () -> Unit,
    onReset: () -> Unit
) {
    // Ask right away, so opening the app goes straight to the system prompt
    LaunchedEffect(Unit) { onUnlock() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Passage",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (firstUse) {
                    "Autentique para criar a chave que protege o cofre neste aparelho."
                } else {
                    "O cofre está bloqueado."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            if (message != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            if (keyInvalidated) {
                Button(onClick = onReset) { Text("Redefinir cofre") }
            } else {
                Button(onClick = onUnlock) { Text(if (firstUse) "Proteger cofre" else "Desbloquear") }
            }
        }
    }
}

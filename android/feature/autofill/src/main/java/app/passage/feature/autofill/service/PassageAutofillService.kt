package app.passage.feature.autofill.service

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import app.passage.core.database.PassageDatabase
import app.passage.core.security.IdentityStore
import app.passage.core.security.VaultLock
import app.passage.crypto.AgeCryptoManager
import app.passage.feature.autofill.R
import app.passage.feature.autofill.parser.AutofillStructureParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class PassageAutofillService : AutofillService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cryptoManager = AgeCryptoManager()
    // Nothing is offered while the vault is locked: reading the identities throws
    private val identityStore by lazy { IdentityStore(applicationContext, VaultLock.getInstance(applicationContext)) }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null)
            return
        }

        val parsedForm = AutofillStructureParser.parse(structure)

        if (parsedForm.passwordField == null && parsedForm.usernameField == null) {
            callback.onSuccess(null)
            return
        }

        serviceScope.launch {
            try {
                val db = PassageDatabase.getInstance(applicationContext)
                val matches = AutofillMatcher.filter(
                    entries = db.secretDao().getAll(),
                    webDomain = parsedForm.webDomain,
                    packageName = parsedForm.packageName,
                    pathOf = { it.relativePath },
                    nameOf = { it.name }
                )

                if (matches.isEmpty()) {
                    callback.onSuccess(null)
                    return@launch
                }

                val responseBuilder = FillResponse.Builder()
                val vaultDir = File(filesDir, "vault")
                val identities = identityStore.getIdentities()

                for (match in matches.take(3)) {
                    val secretFile = File(vaultDir, match.relativePath)
                    if (!secretFile.exists()) continue

                    // Decrypt the secret file to read login and password
                    val encryptedBytes = secretFile.readBytes()
                    val gopassSecret = cryptoManager.decryptSecret(encryptedBytes, identities).getOrNull() ?: continue

                    val presentation = RemoteViews(packageName, R.layout.autofill_inline_suggestion).apply {
                        setTextViewText(R.id.autofill_title, match.name)
                        val subtitle = gopassSecret.attributes["login"] ?: gopassSecret.attributes["email"] ?: "Passage"
                        setTextViewText(R.id.autofill_subtitle, subtitle)
                    }

                    val datasetBuilder = Dataset.Builder(presentation)

                    parsedForm.usernameField?.let { userFieldId ->
                        val username = gopassSecret.attributes["login"]
                            ?: gopassSecret.attributes["email"]
                            ?: gopassSecret.attributes["username"]
                            ?: ""
                        if (username.isNotEmpty()) {
                            datasetBuilder.setValue(userFieldId, AutofillValue.forText(username))
                        }
                    }

                    parsedForm.passwordField?.let { passFieldId ->
                        if (gopassSecret.password.isNotEmpty()) {
                            datasetBuilder.setValue(passFieldId, AutofillValue.forText(gopassSecret.password))
                        }
                    }

                    responseBuilder.addDataset(datasetBuilder.build())
                }

                callback.onSuccess(responseBuilder.build())
            } catch (e: Exception) {
                callback.onSuccess(null)
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Can be used to prompt the user to save newly entered credentials into the vault
        callback.onSuccess()
    }
}

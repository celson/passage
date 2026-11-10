package app.passage.core.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages sensitive clipboard copy operations with Android 13+ sensitive content flags
 * and automatic clipboard wipe after a configurable timeout (default 30 seconds).
 */
class SecureClipboardManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var clearJob: Job? = null

    /**
     * Copies a sensitive string to the clipboard and starts the countdown timer to wipe it.
     */
    fun copySensitiveText(
        label: String,
        sensitiveText: String,
        autoClearSeconds: Int = 30
    ) {
        clearJob?.cancel()

        val clip = ClipData.newPlainText(label, sensitiveText)

        // Mark as sensitive on Android 13+ (API 33+) so it won't be shown in cleartext in the clipboard preview
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

        clipboard.setPrimaryClip(clip)

        // Schedule auto-wipe
        clearJob = scope.launch {
            delay(autoClearSeconds * 1000L)
            wipeClipboardIfMatches(sensitiveText)
        }
    }

    private fun wipeClipboardIfMatches(expectedText: String) {
        // On Android 10+ primaryClip is null while the app is not focused, which is the normal case
        // when the timer fires (the user pasted in another app). An unreadable clip is therefore
        // cleared too; only a readable clip holding different text is left alone.
        val primaryClip = clipboard.primaryClip
        val currentText = if (primaryClip != null && primaryClip.itemCount > 0) {
            primaryClip.getItemAt(0).text?.toString()
        } else {
            null
        }
        val readableAndDifferent = primaryClip != null && primaryClip.itemCount > 0 && currentText != expectedText
        if (readableAndDifferent) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}

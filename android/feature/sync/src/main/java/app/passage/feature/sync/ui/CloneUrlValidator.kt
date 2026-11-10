package app.passage.feature.sync.ui

/** Only transports that are encrypted are accepted; http://, git:// and file:// are not. */
internal object CloneUrlValidator {

    private val scpLike = Regex("^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:(?!//)\\S+$")
    private val schemed = Regex("^(ssh|https)://([^\\s/@]+@)?[A-Za-z0-9.\\[\\]:-]+(/\\S*)?$", RegexOption.IGNORE_CASE)

    fun isAllowed(url: String): Boolean {
        val value = url.trim()
        return schemed.matches(value) || scpLike.matches(value)
    }

    const val ERROR_MESSAGE =
        "URL não permitida: use ssh://, https:// ou o formato usuario@servidor:caminho"
}

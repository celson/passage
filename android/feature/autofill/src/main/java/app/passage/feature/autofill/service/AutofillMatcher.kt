package app.passage.feature.autofill.service

/**
 * Decides which index entries may be offered for a form. Matching is exact on whole path segments:
 * no substring or prefix matching, so `github.evil.com` or a package like `com.evil.a` cannot reach
 * unrelated secrets.
 */
object AutofillMatcher {

    // Only these report a webDomain that is really the page being shown; any other app can claim any domain
    val TRUSTED_BROWSERS = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "com.kiwibrowser.browser"
    )

    /** Returns the entries whose path segments or name identify the requesting site or app. */
    fun <T> filter(
        entries: List<T>,
        webDomain: String?,
        packageName: String?,
        pathOf: (T) -> String,
        nameOf: (T) -> String
    ): List<T> {
        val host = if (packageName != null && packageName in TRUSTED_BROWSERS) normalizeHost(webDomain) else null
        val pkg = packageName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (host == null && pkg == null) return emptyList()

        return entries.filter { entry ->
            val keys = keysOf(pathOf(entry), nameOf(entry))
            if (host != null) keys.any { matchesHost(it, host) } else keys.any { it == pkg }
        }
    }

    internal fun normalizeHost(webDomain: String?): String? =
        webDomain?.trim()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    // A segment matches a host when equal, or when it is a domain (has a dot) the host is a subdomain of
    private fun matchesHost(key: String, host: String): Boolean =
        key == host || (key.contains('.') && host.endsWith(".$key"))

    private fun keysOf(relativePath: String, name: String): Set<String> {
        val segments = relativePath.split('/').map { it.lowercase().removeSuffix(".age") }
        return (segments + name.lowercase().removeSuffix(".age")).filter { it.isNotEmpty() }.toSet()
    }
}

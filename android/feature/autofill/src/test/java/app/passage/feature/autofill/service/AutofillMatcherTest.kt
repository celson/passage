package app.passage.feature.autofill.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillMatcherTest {

    private val chrome = "com.android.chrome"

    private fun match(paths: List<String>, webDomain: String?, pkg: String?): List<String> =
        AutofillMatcher.filter(
            entries = paths,
            webDomain = webDomain,
            packageName = pkg,
            pathOf = { it },
            nameOf = { it.substringAfterLast('/') }
        )

    @Test
    fun secretNamedAfterDomainMatchesDomainAndSubdomain() {
        val paths = listOf("websites/github.com.age")
        assertEquals(paths, match(paths, "github.com", chrome))
        assertEquals(paths, match(paths, "gist.github.com", chrome))
        assertEquals(paths, match(paths, "www.GitHub.com", chrome))
    }

    @Test
    fun folderSegmentMatches() {
        val paths = listOf("github.com/user.age")
        assertEquals(paths, match(paths, "github.com", chrome))
    }

    @Test
    fun bareNameMatchesNothing() {
        assertTrue(match(listOf("github.age", "web/github/user.age"), "github.com", chrome).isEmpty())
    }

    @Test
    fun lookalikeHostsDoNotMatch() {
        val paths = listOf("github.com.age")
        assertTrue(match(paths, "github.com.evil.io", chrome).isEmpty())
        assertTrue(match(paths, "evilgithub.com", chrome).isEmpty())
        assertTrue(match(paths, "github.evil.com", chrome).isEmpty())
    }

    @Test
    fun webDomainFromUntrustedAppIsIgnored() {
        val paths = listOf("github.com.age")
        assertTrue(match(paths, "github.com", "com.evil.app").isEmpty())
        assertTrue(match(paths, "github.com", null).isEmpty())
    }

    @Test
    fun nativeAppNeedsFullPackageName() {
        val paths = listOf("apps/com.example.bank.age", "a.age", "com.evil.age")
        assertEquals(listOf("apps/com.example.bank.age"), match(paths, null, "com.example.bank"))
        assertTrue(match(paths, null, "com.evil.a").isEmpty())
        assertTrue(match(paths, null, "com.example").isEmpty())
    }

    @Test
    fun browserWithoutDomainFallsBackToPackageOnly() {
        assertTrue(match(listOf("github.com.age"), null, chrome).isEmpty())
    }

    @Test
    fun likeWildcardsAreLiteral() {
        val paths = listOf("a.age", "github.com.age")
        assertTrue(match(paths, "%", chrome).isEmpty())
        assertTrue(match(paths, null, "%").isEmpty())
    }

    @Test
    fun emptyInputsMatchNothing() {
        assertTrue(match(listOf("a.age"), "", chrome).isEmpty())
        assertTrue(match(listOf("a.age"), null, "").isEmpty())
    }
}

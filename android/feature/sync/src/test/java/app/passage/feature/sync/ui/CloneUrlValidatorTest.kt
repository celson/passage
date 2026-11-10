package app.passage.feature.sync.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneUrlValidatorTest {

    @Test
    fun acceptsEncryptedTransports() {
        listOf(
            "ssh://git@github.com/user/repo.git",
            "ssh://git@host:2222/user/repo.git",
            "https://github.com/user/repo.git",
            "HTTPS://example.com/repo",
            "git@github.com:user/repo.git",
            "  git@github.com:user/repo.git  "
        ).forEach { assertTrue("should accept '$it'", CloneUrlValidator.isAllowed(it)) }
    }

    @Test
    fun rejectsUnencryptedOrLocalTransports() {
        listOf(
            "http://github.com/user/repo.git",
            "git://github.com/user/repo.git",
            "file:///data/data/other/repo",
            "/sdcard/repo",
            "../repo",
            "ftp://example.com/repo",
            ""
        ).forEach { assertFalse("should reject '$it'", CloneUrlValidator.isAllowed(it)) }
    }

    @Test
    fun rejectsMalformedValues() {
        listOf("github.com/user/repo", "ssh://", "https://", "git@host:", "a b@host:path", "ssh:// git@host/x")
            .forEach { assertFalse("should reject '$it'", CloneUrlValidator.isAllowed(it)) }
    }
}

package app.passage.feature.editor.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SecretNameValidatorTest {

    @Test
    fun acceptsOrdinaryNames() {
        assertNull(SecretNameValidator.validate("github.com"))
        assertNull(SecretNameValidator.validate("my secret"))
        assertNull(SecretNameValidator.validate("a..b"))
    }

    @Test
    fun rejectsEmpty() {
        assertNotNull(SecretNameValidator.validate(""))
    }

    @Test
    fun rejectsPathSyntax() {
        listOf("a/b", "../x", "a\\b", "a\u0000b", ".", "..").forEach {
            assertNotNull("should reject '$it'", SecretNameValidator.validate(it))
        }
    }

    @Test
    fun rejectsGitNames() {
        listOf(".git", ".gitignore", ".gitmodules").forEach {
            assertNotNull("should reject '$it'", SecretNameValidator.validate(it))
        }
    }
}

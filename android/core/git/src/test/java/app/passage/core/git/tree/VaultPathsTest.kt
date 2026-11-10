package app.passage.core.git.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class VaultPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun vault(): File = tmp.newFolder("vault")

    @Test
    fun resolvesNormalAndNestedPaths() {
        val vault = vault()
        assertEquals(File(vault.canonicalFile, "a.age"), VaultPaths.resolve(vault, "a.age"))
        assertEquals(File(vault.canonicalFile, "web/b.age"), VaultPaths.resolve(vault, "web/b.age"))
        assertEquals(File(vault.canonicalFile, "web/b.age"), VaultPaths.resolve(vault, "web/../web/b.age"))
    }

    @Test
    fun rejectsTraversalOutsideVault() {
        val vault = vault()
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, "../x.age") }
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, "a/../../x.age") }
    }

    @Test
    fun rejectsSiblingDirectorySharingThePrefix() {
        val vault = vault()
        tmp.newFolder("vault-evil")
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, "../vault-evil/x.age") }
    }

    @Test
    fun rejectsVaultRootItself() {
        val vault = vault()
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, "") }
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, ".") }
    }

    @Test
    fun rejectsGitDirectory() {
        val vault = vault()
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, ".git/config") }
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, "a/../.git/hooks/pre-commit") }
    }

    @Test
    fun rejectsSymlinkPointingOutsideVault() {
        val vault = vault()
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(vault, "link").toPath(), outside.toPath())
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, "link/secret.age") }
        assertThrows(SecurityException::class.java) { VaultPaths.resolve(vault, "link") }
    }

    @Test
    fun allowsSymlinkStayingInsideVault() {
        val vault = vault()
        val real = File(vault, "real").also { it.mkdirs() }
        Files.createSymbolicLink(File(vault, "alias").toPath(), real.toPath())
        assertEquals(File(vault.canonicalFile, "real/s.age"), VaultPaths.resolve(vault, "alias/s.age"))
    }
}

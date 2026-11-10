package app.passage.core.git.tree

import java.io.File

/**
 * Resolves secret paths inside the vault. Paths come from the UI and from the repository content,
 * so `..` segments and symlinks committed to a malicious remote must not lead outside the vault.
 */
object VaultPaths {

    /**
     * Returns the canonical file for [relativePath], or throws [SecurityException] if it is not
     * strictly inside [vaultDir] (after following symlinks) or lies within `.git`.
     */
    fun resolve(vaultDir: File, relativePath: String): File {
        val root = vaultDir.canonicalFile
        val target = File(root, relativePath).canonicalFile
        val relative = target.relativeToOrNull(root)
        if (relative == null || relative.path.isEmpty() || relative.path.split(File.separatorChar).first() == "..") {
            throw SecurityException("Caminho fora do cofre: $relativePath")
        }
        if (relative.path.split(File.separatorChar).any { it == ".git" }) {
            throw SecurityException("Caminho protegido: $relativePath")
        }
        return target
    }
}

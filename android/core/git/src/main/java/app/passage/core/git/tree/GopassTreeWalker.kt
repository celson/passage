package app.passage.core.git.tree

import java.io.File

sealed class VaultItem {
    abstract val name: String
    abstract val relativePath: String

    data class Folder(
        override val name: String,
        override val relativePath: String,
        val children: List<VaultItem>
    ) : VaultItem()

    data class SecretFile(
        override val name: String,
        override val relativePath: String,
        val lastModifiedMs: Long,
        val fileSize: Long
    ) : VaultItem()
}

data class SecretMetadata(
    val name: String,
    val relativePath: String,
    val parentFolder: String,
    val lastModifiedMs: Long
)

class GopassTreeWalker {

    /**
     * Builds a hierarchical tree of folders and secret files starting from [repoRoot].
     */
    fun buildTree(repoRoot: File): VaultItem.Folder {
        if (!repoRoot.exists() || !repoRoot.isDirectory) {
            return VaultItem.Folder(name = "root", relativePath = "", children = emptyList())
        }
        return scanFolder(repoRoot, repoRoot)
    }

    /**
     * Returns a flat list of all secrets in the repository for fast search indexing.
     */
    fun listAllSecrets(repoRoot: File): List<SecretMetadata> {
        val results = mutableListOf<SecretMetadata>()
        if (!repoRoot.exists()) return results

        repoRoot.walkTopDown()
            .filter { !it.name.startsWith(".git") && it.isFile && it.name.endsWith(".age") }
            .forEach { file ->
                val relativePath = file.relativeTo(repoRoot).path
                val parentFolder = file.parentFile?.relativeTo(repoRoot)?.path ?: ""
                val cleanName = file.name.removeSuffix(".age")

                results.add(
                    SecretMetadata(
                        name = cleanName,
                        relativePath = relativePath,
                        parentFolder = parentFolder,
                        lastModifiedMs = file.lastModified()
                    )
                )
            }

        return results.sortedBy { it.relativePath }
    }

    /**
     * Resolves recipients for a secret by traversing directories upwards from the secret location
     * to the repo root looking for `.age-recipients` or `.gopass-recipients`.
     */
    fun findRecipientsForPath(repoRoot: File, relativePath: String): List<String> =
        findRecipientsFileForPath(repoRoot, relativePath)?.recipients ?: emptyList()

    /**
     * Same lookup as [findRecipientsForPath], also telling which recipients file was used.
     */
    fun findRecipientsFileForPath(repoRoot: File, relativePath: String): RecipientsFile? {
        val secretFile = File(repoRoot, relativePath)
        var currentDir = if (secretFile.isDirectory) secretFile else secretFile.parentFile

        while (currentDir != null && currentDir.startsWith(repoRoot)) {
            for (name in RECIPIENTS_FILE_NAMES) {
                val recipientsFile = File(currentDir, name)
                if (recipientsFile.exists() && recipientsFile.isFile) {
                    return RecipientsFile(recipientsFile.relativeTo(repoRoot).path, parseRecipientsFileLines(recipientsFile))
                }
            }

            if (currentDir == repoRoot) break
            currentDir = currentDir.parentFile
        }

        return null
    }

    /**
     * Lists every recipients file of the vault.
     */
    fun listRecipientsFiles(repoRoot: File): List<RecipientsFile> {
        if (!repoRoot.exists()) return emptyList()

        return repoRoot.walkTopDown()
            .onEnter { it == repoRoot || !it.name.startsWith(".git") }
            .filter { it.isFile && it.name in RECIPIENTS_FILE_NAMES }
            .map { RecipientsFile(it.relativeTo(repoRoot).path, parseRecipientsFileLines(it)) }
            .sortedBy { it.relativePath }
            .toList()
    }

    private fun scanFolder(folder: File, repoRoot: File): VaultItem.Folder {
        val children = mutableListOf<VaultItem>()
        val files = folder.listFiles() ?: emptyArray()

        // 1. Scan subfolders first (ignore .git)
        files.filter { it.isDirectory && !it.name.startsWith(".git") }
            .sortedBy { it.name.lowercase() }
            .forEach { subDir ->
                val subTree = scanFolder(subDir, repoRoot)
                // Only include folders that have content
                if (subTree.children.isNotEmpty()) {
                    children.add(subTree)
                }
            }

        // 2. Scan .age files
        files.filter { it.isFile && it.name.endsWith(".age") }
            .sortedBy { it.name.lowercase() }
            .forEach { file ->
                val relativePath = file.relativeTo(repoRoot).path
                children.add(
                    VaultItem.SecretFile(
                        name = file.name.removeSuffix(".age"),
                        relativePath = relativePath,
                        lastModifiedMs = file.lastModified(),
                        fileSize = file.length()
                    )
                )
            }

        val relativePath = if (folder == repoRoot) "" else folder.relativeTo(repoRoot).path
        val folderName = if (folder == repoRoot) "Root" else folder.name

        return VaultItem.Folder(
            name = folderName,
            relativePath = relativePath,
            children = children
        )
    }

    private fun parseRecipientsFileLines(file: File): List<String> {
        return file.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private companion object {
        val RECIPIENTS_FILE_NAMES = listOf(".age-recipients", ".gopass-recipients")
    }
}

package app.passage.core.git.model

import app.passage.core.git.ssh.HostKeyInfo

sealed class SyncResult {
    data class Success(val commitHash: String?, val message: String) : SyncResult()
    data class HasConflicts(val conflictedFiles: List<String>) : SyncResult()
    data class Failure(val error: Throwable) : SyncResult()
}

data class GitRepoStatus(
    val isClean: Boolean,
    val hasUncommittedChanges: Boolean,
    val uncommittedFiles: List<String>,
    val aheadCount: Int,
    val behindCount: Int,
    val currentBranch: String
)

enum class ConflictStrategy {
    /**
     * Discard local changes and accept the incoming remote version.
     */
    THEIRS,

    /**
     * Keep the local version and discard the incoming remote version.
     */
    OURS,

    /**
     * Preserve local version as <name>.conflict.age and keep remote version as <name>.age.
     * Allows the user to inspect both versions without data loss.
     */
    KEEP_BOTH
}

sealed class GitException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthenticationFailed(message: String, cause: Throwable? = null) : GitException(message, cause)
    class RemoteNotFound(message: String, cause: Throwable? = null) : GitException(message, cause)
    class MergeConflict(val conflictedFiles: List<String>) : GitException("Merge conflicts detected in: $conflictedFiles")
    class NetworkError(message: String, cause: Throwable? = null) : GitException(message, cause)
    /** The server is not pinned yet: the user must compare and approve [key] before connecting. */
    class UnknownHostKey(val key: HostKeyInfo) :
        GitException("Chave de host SSH desconhecida para ${key.host}: ${key.fingerprint}")
    /** The server is pinned to another key: possible man-in-the-middle. */
    class HostKeyChanged(val key: HostKeyInfo) :
        GitException("A chave de host SSH de ${key.host} mudou: ${key.fingerprint}")
    class OperationFailed(message: String, cause: Throwable? = null) : GitException(message, cause)
}

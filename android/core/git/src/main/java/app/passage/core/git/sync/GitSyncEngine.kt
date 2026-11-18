package app.passage.core.git.sync

import app.passage.core.git.model.ConflictStrategy
import app.passage.core.git.model.GitAuth
import app.passage.core.git.model.GitException
import app.passage.core.git.model.GitRepoStatus
import app.passage.core.git.model.SyncResult
import app.passage.core.git.ssh.CustomSshSessionFactory
import app.passage.core.git.ssh.PinnedHostKeyStore
import app.passage.core.git.ssh.PinningHostKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitSyncEngine(private val hostKeyStore: PinnedHostKeyStore) {

    /**
     * Clones a remote git repository into [destinationDir].
     */
    suspend fun clone(
        remoteUrl: String,
        destinationDir: File,
        auth: GitAuth,
        onProgress: ((task: String, percent: Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var hostKeys: PinningHostKeyRepository? = null
        runCatching {
            if (destinationDir.exists() && destinationDir.list()?.isNotEmpty() == true) {
                throw GitException.OperationFailed("Destination directory is not empty: ${destinationDir.absolutePath}")
            }

            val cloneCommand = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(destinationDir)
                .setProgressMonitor(createProgressMonitor(onProgress))

            hostKeys = configureAuthentication(cloneCommand, auth)
            val git = cloneCommand.call()
            git.close()
        }.recoverCatching { error -> throw hostKeyFailure(hostKeys) ?: error }
    }

    /**
     * Performs a Pull (fetch + merge fast-forward) from the remote tracking branch.
     */
    suspend fun pull(
        repoDir: File,
        auth: GitAuth
    ): SyncResult = withContext(Dispatchers.IO) {
        var hostKeys: PinningHostKeyRepository? = null
        try {
            Git.open(repoDir).use { git ->
                // A merge left unfinished (by an older version, for instance) blocks any pull
                when (git.repository.repositoryState) {
                    RepositoryState.MERGING_RESOLVED -> concludeMerge(git, DEFAULT_AUTHOR_NAME, DEFAULT_AUTHOR_EMAIL)
                    RepositoryState.MERGING -> return@withContext SyncResult.HasConflicts(conflictedFiles(git))
                    else -> Unit
                }

                val pullCmd = git.pull()
                    .setFastForward(MergeCommand.FastForwardMode.FF)

                hostKeys = configureAuthentication(pullCmd, auth)
                val pullResult = pullCmd.call()

                if (pullResult.isSuccessful) {
                    val mergeResult = pullResult.mergeResult
                    val commitId = mergeResult?.newHead?.name
                    SyncResult.Success(commitId, "Repository updated successfully")
                } else {
                    val conflicts = pullResult.mergeResult?.conflicts?.keys?.toList() ?: emptyList()
                    if (conflicts.isNotEmpty()) {
                        SyncResult.HasConflicts(conflicts)
                    } else {
                        SyncResult.Failure(
                            GitException.OperationFailed(
                                "Pull failed with status: ${pullResult.mergeResult?.mergeStatus}"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            SyncResult.Failure(hostKeyFailure(hostKeys) ?: mapException(e))
        }
    }

    /**
     * Stages changes, creates a commit, and pushes to remote repository.
     */
    suspend fun commitAndPush(
        repoDir: File,
        message: String,
        authorName: String = "Passage Android",
        authorEmail: String = "passage@local",
        auth: GitAuth,
        filesToStage: List<String>? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        var hostKeys: PinningHostKeyRepository? = null
        try {
            Git.open(repoDir).use { git ->
                val repo = git.repository

                // Staging would silently "resolve" files that still hold unresolved conflicts
                if (repo.repositoryState == RepositoryState.MERGING) {
                    val conflicts = conflictedFiles(git)
                    if (conflicts.isNotEmpty()) return@withContext SyncResult.HasConflicts(conflicts)
                }

                // 1. Stage files
                val addCmd = git.add()
                if (filesToStage.isNullOrEmpty()) {
                    addCmd.addFilepattern(".")
                } else {
                    filesToStage.forEach { addCmd.addFilepattern(it) }
                }
                addCmd.call()

                // Also stage deleted files
                git.add().setUpdate(true).addFilepattern(".").call()

                // 2. Commit only when there is something to record: a resolved merge always needs
                // its commit, but a clean tree must not produce an empty "sync" commit
                val concludesMerge = repo.repositoryState == RepositoryState.MERGING_RESOLVED
                val commit = if (git.status().call().hasUncommittedChanges() || concludesMerge) {
                    git.commit()
                        .setMessage(message)
                        .setAuthor(authorName, authorEmail)
                        .call()
                } else {
                    null
                }

                val ahead = BranchTrackingStatus.of(repo, repo.branch)?.aheadCount ?: 0
                if (commit == null && ahead == 0) {
                    return@withContext SyncResult.Success(
                        repo.resolve(Constants.HEAD)?.name,
                        "Nothing to push: already up to date"
                    )
                }

                // 3. Push
                val pushCmd = git.push()
                hostKeys = configureAuthentication(pushCmd, auth)
                val pushResults = pushCmd.call()

                val pushSuccess = pushResults.all { result ->
                    result.remoteUpdates.all {
                        it.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK ||
                                it.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
                    }
                }

                if (pushSuccess) {
                    if (commit != null) {
                        SyncResult.Success(commit.name, "Committed and pushed: ${commit.shortMessage}")
                    } else {
                        SyncResult.Success(repo.resolve(Constants.HEAD)?.name, "Pushed $ahead commit(s)")
                    }
                } else {
                    val errDetails = pushResults.flatMap { it.remoteUpdates }
                        .map { "${it.remoteName}: ${it.status} (${it.message ?: "no message"})" }
                        .joinToString("; ")
                    SyncResult.Failure(GitException.OperationFailed("Push rejected: $errDetails"))
                }
            }
        } catch (e: Exception) {
            SyncResult.Failure(hostKeyFailure(hostKeys) ?: mapException(e))
        }
    }

    /**
     * Resolves a binary merge conflict on an encrypted .age secret file.
     */
    suspend fun resolveConflict(
        repoDir: File,
        relativePath: String,
        strategy: ConflictStrategy
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(repoDir).use { git ->
                when (strategy) {
                    ConflictStrategy.THEIRS -> {
                        git.checkout()
                            .setStage(CheckoutCommand.Stage.THEIRS)
                            .addPath(relativePath)
                            .call()
                        git.add().addFilepattern(relativePath).call()
                    }

                    ConflictStrategy.OURS -> {
                        git.checkout()
                            .setStage(CheckoutCommand.Stage.OURS)
                            .addPath(relativePath)
                            .call()
                        git.add().addFilepattern(relativePath).call()
                    }

                    ConflictStrategy.KEEP_BOTH -> {
                        // 1. Extract ours to temporary conflict file
                        val conflictFile = File(
                            repoDir,
                            relativePath.removeSuffix(".age") + ".conflict.age"
                        )
                        val originalFile = File(repoDir, relativePath)
                        originalFile.copyTo(conflictFile, overwrite = true)

                        // 2. Checkout theirs for the primary file
                        git.checkout()
                            .setStage(CheckoutCommand.Stage.THEIRS)
                            .addPath(relativePath)
                            .call()

                        // 3. Stage both files
                        git.add().addFilepattern(relativePath).call()
                        git.add().addFilepattern(conflictFile.relativeTo(repoDir).path).call()
                    }
                }

                // Last conflict resolved: record the merge, otherwise the repository stays in
                // MERGING_RESOLVED and refuses to pull
                if (git.repository.repositoryState == RepositoryState.MERGING_RESOLVED) {
                    concludeMerge(git, DEFAULT_AUTHOR_NAME, DEFAULT_AUTHOR_EMAIL)
                }
            }
            Unit
        }
    }

    /**
     * Retrieves the repository status (uncommitted files, ahead/behind counts).
     */
    suspend fun getStatus(repoDir: File): Result<GitRepoStatus> = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(repoDir).use { git ->
                val repo = git.repository
                val status = git.status().call()
                val branch = repo.branch ?: "unknown"

                val trackingStatus = BranchTrackingStatus.of(repo, branch)
                val ahead = trackingStatus?.aheadCount ?: 0
                val behind = trackingStatus?.behindCount ?: 0

                val uncommitted = mutableListOf<String>()
                uncommitted.addAll(status.uncommittedChanges)
                uncommitted.addAll(status.untracked)

                GitRepoStatus(
                    isClean = status.isClean,
                    hasUncommittedChanges = status.hasUncommittedChanges(),
                    uncommittedFiles = uncommitted.distinct(),
                    aheadCount = ahead,
                    behindCount = behind,
                    currentBranch = branch
                )
            }
        }
    }

    private fun concludeMerge(git: Git, authorName: String, authorEmail: String) {
        git.commit()
            .setMessage("Merge remote changes (resolved in Passage)")
            .setAuthor(authorName, authorEmail)
            .call()
    }

    private fun conflictedFiles(git: Git): List<String> = git.status().call().conflicting.toList()

    private companion object {
        const val DEFAULT_AUTHOR_NAME = "Passage Android"
        const val DEFAULT_AUTHOR_EMAIL = "passage@local"
    }

    // Helper: configure Transport and Credentials for any JGit command.
    // Returns the host key check used by SSH connections, which records the key it refused.
    private fun <C : org.eclipse.jgit.api.TransportCommand<C, *>> configureAuthentication(
        command: C,
        auth: GitAuth
    ): PinningHostKeyRepository? {
        fun useSshKey(privateKeyContent: String, passphrase: String?): PinningHostKeyRepository {
            val hostKeys = PinningHostKeyRepository(hostKeyStore)
            val sessionFactory = CustomSshSessionFactory(
                privateKeyContent = privateKeyContent,
                passphrase = passphrase,
                hostKeys = hostKeys
            )
            command.setTransportConfigCallback(TransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = sessionFactory
                }
            })
            return hostKeys
        }

        return when (auth) {
            is GitAuth.SshKey -> useSshKey(auth.privateKeyContent, auth.passphrase)

            is GitAuth.SshKeyFile -> useSshKey(auth.keyFile.readText(Charsets.UTF_8), auth.passphrase)

            is GitAuth.HttpToken -> {
                val credentialsProvider: CredentialsProvider =
                    UsernamePasswordCredentialsProvider(auth.username, auth.token)
                command.setCredentialsProvider(credentialsProvider)
                null
            }

            is GitAuth.None -> null // Anonymous access
        }
    }

    private fun hostKeyFailure(hostKeys: PinningHostKeyRepository?): GitException? =
        hostKeys?.rejected?.let { rejected ->
            if (rejected.changed) GitException.HostKeyChanged(rejected.key) else GitException.UnknownHostKey(rejected.key)
        }

    private fun createProgressMonitor(callback: ((String, Float) -> Unit)?): ProgressMonitor {
        return object : ProgressMonitor {
            private var currentTask = ""
            private var totalWork = 0
            private var completedWork = 0

            override fun start(totalTasks: Int) {}

            override fun beginTask(title: String, totalWork: Int) {
                this.currentTask = title
                this.totalWork = totalWork
                this.completedWork = 0
                callback?.invoke(currentTask, 0f)
            }

            override fun update(completed: Int) {
                this.completedWork += completed
                val progress = if (totalWork > 0) completedWork.toFloat() / totalWork else 0f
                callback?.invoke(currentTask, progress)
            }

            override fun endTask() {
                callback?.invoke(currentTask, 1f)
            }

            override fun isCancelled(): Boolean = false
            override fun showDuration(enabled: Boolean) {}
        }
    }

    private fun mapException(e: Exception): GitException {
        val msg = e.message ?: "Unknown git error"
        return when {
            msg.contains("Auth fail", ignoreCase = true) || msg.contains("not authorized", ignoreCase = true) ->
                GitException.AuthenticationFailed("Git authentication failed: check credentials/keys", e)

            msg.contains("Connection refused", ignoreCase = true) || msg.contains("UnknownHostException", ignoreCase = true) ->
                GitException.NetworkError("Network error: unable to connect to remote host", e)

            else -> GitException.OperationFailed(msg, e)
        }
    }
}

package app.passage.core.git.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.util.FS

/**
 * Custom JGit SSH Session Factory that loads the private key from memory
 * and only connects to servers whose host key is pinned in [hostKeys].
 */
class CustomSshSessionFactory(
    private val privateKeyContent: String,
    private val passphrase: String? = null,
    private val hostKeys: PinningHostKeyRepository
) : JschConfigSessionFactory() {

    override fun configure(hc: OpenSshConfig.Host?, session: Session?) {
        // Unknown or changed host keys abort the connection; the user approves them explicitly
        session?.setConfig("StrictHostKeyChecking", "yes")
    }

    override fun createDefaultJSch(fs: FS?): JSch {
        SshKeyManager.installEd25519Provider()
        val jsch = super.createDefaultJSch(fs)
        jsch.hostKeyRepository = hostKeys

        val privateKeyBytes = privateKeyContent.toByteArray(Charsets.UTF_8)
        val passphraseBytes = passphrase?.toByteArray(Charsets.UTF_8)

        jsch.removeAllIdentity()
        jsch.addIdentity(
            "passage-id",
            privateKeyBytes,
            null, // Public key is derived by JSch
            passphraseBytes
        )

        return jsch
    }
}

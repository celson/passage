package app.passage.core.git

import app.passage.core.git.ssh.HostKeyInfo
import app.passage.core.git.ssh.PinnedHostKeyStore
import app.passage.core.git.ssh.PinningHostKeyRepository
import app.passage.core.git.tree.GopassTreeWalker
import app.passage.core.git.tree.RecipientTrustStore
import app.passage.core.git.tree.RecipientsFile
import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class TrustStoresTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ed25519Blob(fill: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).run {
            val type = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
            writeInt(type.size)
            write(type)
            writeInt(32)
            write(ByteArray(32) { fill.toByte() })
        }
        return out.toByteArray()
    }

    @Test
    fun unknownHostIsRefusedAndReported() {
        val repository = PinningHostKeyRepository(PinnedHostKeyStore(File(tmp.root, "trust/known_hosts")))

        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("github.com", ed25519Blob(1)))

        val rejected = repository.rejected!!
        assertFalse(rejected.changed)
        assertEquals("ssh-ed25519", rejected.key.keyType)
        assertTrue(rejected.key.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun pinnedHostIsAcceptedOnlyWithTheSameKey() {
        val store = PinnedHostKeyStore(File(tmp.root, "known_hosts"))
        store.trust(HostKeyInfo.of("github.com", ed25519Blob(1)))

        val repository = PinningHostKeyRepository(PinnedHostKeyStore(File(tmp.root, "known_hosts")))
        assertEquals(HostKeyRepository.OK, repository.check("github.com", ed25519Blob(1)))
        assertNull(repository.rejected)

        assertEquals(HostKeyRepository.CHANGED, repository.check("github.com", ed25519Blob(2)))
        assertTrue(repository.rejected!!.changed)

        // The pin of one host says nothing about another
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("gitlab.com", ed25519Blob(1)))
    }

    @Test
    fun forgettingAHostRequiresApprovalAgain() {
        val store = PinnedHostKeyStore(File(tmp.root, "known_hosts"))
        store.trust(HostKeyInfo.of("github.com", ed25519Blob(1)))
        store.forget("github.com")

        val repository = PinningHostKeyRepository(store)
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("github.com", ed25519Blob(2)))
        assertFalse(repository.rejected!!.changed)
    }

    @Test
    fun recipientsAreTrustedOnlyWhileTheyMatchTheApprovedSet() {
        val trust = RecipientTrustStore(File(tmp.root, "trust/recipients"))
        val approved = RecipientsFile(".age-recipients", listOf("age1mine", "ssh-ed25519 AAAA backup@laptop"))

        assertFalse(trust.isTrusted(approved))
        assertTrue(trust.pendingChanges(listOf(approved)).single().isNew)

        trust.approve(listOf(approved))
        val reloaded = RecipientTrustStore(File(tmp.root, "trust/recipients"))
        assertTrue(reloaded.isTrusted(approved))
        assertTrue(reloaded.isTrusted(approved.copy(recipients = approved.recipients.reversed())))
        assertTrue(reloaded.pendingChanges(listOf(approved)).isEmpty())

        val tampered = approved.copy(recipients = approved.recipients + "age1attacker")
        assertFalse(reloaded.isTrusted(tampered))
        val change = reloaded.pendingChanges(listOf(tampered)).single()
        assertFalse(change.isNew)
        assertEquals(listOf("age1attacker"), change.added)
        assertTrue(change.removed.isEmpty())

        // Approving one file does not cover another
        assertFalse(reloaded.isTrusted(RecipientsFile("Business/.age-recipients", approved.recipients)))
    }

    @Test
    fun walkerReportsWhichRecipientsFileAppliesToASecret() {
        val vault = tmp.newFolder("vault")
        File(vault, ".age-recipients").writeText("# root\nage1root\n")
        File(vault, "Business").mkdirs()
        File(vault, "Business/.age-recipients").writeText("age1business\n")
        File(vault, ".git").mkdirs()
        File(vault, ".git/.age-recipients").writeText("age1ignored\n")

        val walker = GopassTreeWalker()
        assertEquals(
            RecipientsFile("Business/.age-recipients", listOf("age1business")),
            walker.findRecipientsFileForPath(vault, "Business/site.age")
        )
        assertEquals(
            RecipientsFile(".age-recipients", listOf("age1root")),
            walker.findRecipientsFileForPath(vault, "Personal/site.age")
        )
        assertEquals(
            listOf(".age-recipients", "Business/.age-recipients"),
            walker.listRecipientsFiles(vault).map { it.relativePath }
        )
    }
}

package tech.elephantclock.privatemail.core.data.settings

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.onboarding.BackfillResult
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget

/** T48's carried-over items against GreenMail: per-folder switches, straight to Trash, custom folders, roles, backfill re-run. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RulesMailTest {
    private val env = RulesTestEnv()

    @After
    fun tearDown() = env.close()

    private suspend fun seedRules() {
        env.rule("billing@bank.example", FolderTarget(FolderRole.MONEY))
        env.rule("tickets@jira.example", FolderTarget(FolderRole.WORK))
        env.rule("deals@promo.example", FolderTarget.Discarded)
        env.deliver("billing@bank.example", "Invoice")
        env.deliver("tickets@jira.example", "Ticket")
        env.deliver("deals@promo.example", "Sale")
        env.repo.syncAccount(env.accountId)
    }

    @Test
    fun `a switched-off folder keeps only its own mail in the Inbox, other folders and discard still move`() =
        runBlocking {
            env.readyForAutoFile()
            env.store.update(env.accountId) { it.copy(disabledFolders = setOf(FolderRole.MONEY)) }
            seedRules()

            env.orchestrator.drain()

            assertEquals(listOf("Invoice"), env.serverSubjects("INBOX"))
            assertEquals(listOf("Ticket"), env.serverSubjects("Work"))
            assertEquals(listOf("Sale"), env.serverSubjects("Discarded"))
            assertFalse("nothing was filed into the switched-off folder", env.hasFolder("Money"))
        }

    @Test
    fun `folders triage creates get their role, so the sync keeps them current`() =
        runBlocking {
            env.readyForAutoFile()
            seedRules()

            env.orchestrator.drain()

            assertEquals(FolderRole.MONEY, env.roleOf("Money"))
            assertEquals(FolderRole.WORK, env.roleOf("Work"))
            assertEquals(FolderRole.DISCARDED, env.roleOf("Discarded"))
            env.repo.syncAccount(env.accountId)
            // Another client deletes the invoice in Money: the next sync reconciles the moved row.
            env.server.otherClient("Money") { f, _ ->
                f.messages.forEach { it.setFlag(jakarta.mail.Flags.Flag.DELETED, true) }
            }
            env.repo.syncAccount(env.accountId)
            val rows = env.db.query("SELECT COUNT(*) FROM messages WHERE folderPath = 'Money'", null)
            rows.use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }

    @Test
    fun `straight to Trash moves discarded mail into the existing Trash and drops the rows`() =
        runBlocking {
            env.server.folder("Trash")
            env.readyForAutoFile()
            env.store.update(env.accountId) { it.copy(discardMode = DiscardMode.STRAIGHT_TO_TRASH) }
            seedRules()

            val report = env.orchestrator.drain()

            assertEquals(listOf("Sale"), env.serverSubjects("Trash"))
            assertTrue(env.serverSubjects("Discarded").isEmpty())
            assertEquals(1, report.moves.trashed)
            assertEquals(listOf("Ticket"), env.serverSubjects("Work"))
            val rows = env.db.query("SELECT COUNT(*) FROM messages WHERE subject = 'Sale'", null)
            rows.use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }

    @Test
    fun `straight to Trash without a Trash folder holds the mail in Discarded and never creates Trash`() =
        runBlocking {
            env.readyForAutoFile()
            env.store.update(env.accountId) { it.copy(discardMode = DiscardMode.STRAIGHT_TO_TRASH) }
            seedRules()

            val report = env.orchestrator.drain()

            assertFalse(env.hasFolder("Trash"))
            assertEquals(listOf("Sale"), env.serverSubjects("Discarded"))
            assertEquals(1, report.moves.heldNoTrash)
            assertEquals(0, report.moves.trashed)
        }

    @Test
    fun `a custom folder is created on first use, gets the CUSTOM role, and follows its switch`() =
        runBlocking {
            env.readyForAutoFile()
            env.store.update(env.accountId) {
                it.copy(customFolders = listOf(CustomFolder("Partners", "Anyone asking to integrate or resell")))
            }
            env.rule("bd@acme.example", FolderTarget.custom("Partners"))
            env.rule("ops@acme.example", FolderTarget.custom("Partners"))
            assertFalse(env.hasFolder("Partners"))
            val guides = env.settings.forAccount(env.accountId).customFolders
            assertEquals(listOf(FolderTarget.custom("Partners")), guides.map { it.target })
            assertEquals("Anyone asking to integrate or resell", guides.single().description)

            env.deliver("bd@acme.example", "Integration")
            env.repo.syncAccount(env.accountId, listOf("INBOX"))
            env.orchestrator.drain()
            assertEquals(listOf("Integration"), env.serverSubjects("Partners"))
            assertEquals(FolderRole.CUSTOM, env.roleOf("Partners"))

            env.store.update(
                env.accountId,
            ) { p -> p.copy(customFolders = p.customFolders.map { it.copy(enabled = false) }) }
            env.deliver("ops@acme.example", "Second")
            env.repo.syncAccount(env.accountId, listOf("INBOX"))
            env.orchestrator.drain()
            assertEquals(listOf("Second"), env.serverSubjects("INBOX"))
        }

    @Test
    fun `a Sent UIDVALIDITY reset closes the gate and the re-run opens it again`() =
        runBlocking {
            env.readyForAutoFile()
            assertEquals(BackfillStatus.COMPLETE, env.gate.status(env.accountId))
            assertTrue("nothing to do while complete", env.rerun.run().isEmpty())

            // The server recreates Sent: a new UIDVALIDITY; the sync drops the old history.
            val manager = env.server.greenMail.managers.imapHostManager
            manager.deleteMailbox(env.server.user, "Sent")
            Thread.sleep(1_100) // GreenMail derives UIDVALIDITY from the clock in seconds
            env.server.folder("Sent")
            env.repo.syncAccount(env.accountId)
            assertEquals(BackfillStatus.PENDING, env.gate.status(env.accountId))
            assertFalse(
                env.settings
                    .forAccount(env.accountId)
                    .autoFile.enabled,
            )

            val results = env.rerun.run()

            assertEquals(BackfillResult.Complete, results[env.accountId])
            assertEquals(BackfillStatus.COMPLETE, env.gate.status(env.accountId))
            assertTrue(
                env.settings
                    .forAccount(env.accountId)
                    .autoFile.enabled,
            )
        }

    @Test
    fun `the re-run leaves accounts alone that never asked for auto-file or wait for the user`() =
        runBlocking {
            env.readyForAutoFile()
            env.store.update(env.accountId) { it.copy(autoFileRequested = false, backfill = emptyMap()) }
            assertTrue(env.rerun.run().isEmpty())
            env.store.update(env.accountId) { it.copy(autoFileRequested = true) }
            env.db.accounts().setAuthProblem(env.accountId.value, "WrongPassword")
            assertTrue(env.rerun.run().isEmpty())
        }
}

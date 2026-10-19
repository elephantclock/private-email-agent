package tech.elephantclock.privatemail.core.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource
import java.nio.file.Files

class TriageSettingsStoreTest {
    private val dir = Files.createTempDirectory("prefs").toFile()
    private val file = dir.resolve("triage.json")
    private val scopes = mutableListOf<CoroutineScope>()
    private val a = AccountId("a")
    private val b = AccountId("b")

    private fun store(): TriageSettingsStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return TriageSettingsStore(TriageSettingsStore.create(scope) { file })
    }

    /** A second store on the same file must wait for the first to be gone (DataStore's rule). */
    private fun reopen(): TriageSettingsStore {
        scopes.forEach { it.cancel() }
        scopes.clear()
        Thread.sleep(50)
        return store()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    @Test
    fun `settings survive a restart, per account`() =
        runBlocking {
            val prefs =
                AccountPrefs(
                    step = OnboardingStep.FIRST_RUN,
                    vips = setOf("boss@corp.example"),
                    disabledFolders = setOf(FolderRole.WORK),
                    customFolders = listOf(CustomFolder("Partners", "anyone asking to integrate or resell")),
                    discardMode = DiscardMode.STRAIGHT_TO_TRASH,
                    backfill = mapOf("Sent" to FolderBackfill(uidValidity = 7, belowUid = 40, total = 90, stored = 50)),
                )
            store().update(a) { prefs }

            val again = reopen()
            assertEquals(prefs, again.get(a))
            assertEquals("other accounts get the safe defaults", AccountPrefs(), again.get(b))
            assertEquals(prefs, again.account(a).first())
        }

    @Test
    fun `a corrupt file falls back to the safe defaults`() =
        runBlocking {
            file.writeText("{not json")
            val prefs = store().get(a)
            assertEquals(AccountPrefs(), prefs)
            assertEquals(false, prefs.autoFileRequested)
        }

    @Test
    fun `updates are read-modify-write`() =
        runBlocking {
            val s = store()
            s.update(a) { it.copy(vips = it.vips + "x@y.example") }
            s.update(a) { it.copy(vips = it.vips + "z@y.example") }
            s.update(b) { it.copy(step = OnboardingStep.DONE) }
            assertEquals(setOf("x@y.example", "z@y.example"), s.get(a).vips)
            assertEquals(OnboardingStep.DONE, s.get(b).step)
            s.remove(b)
            assertEquals(AccountPrefs(), s.get(b))
        }

    @Test
    fun `a file written before T48 still decodes, keeping its backfill cursors`() =
        runBlocking {
            file.writeText(
                """{"accounts":{"a":{"step":"DONE","disabledFolders":["WORK"],""" +
                    """"customFolders":[{"name":"Partners","description":"resellers"}],"autoFileRequested":true,""" +
                    """"backfill":{"Sent":{"uidValidity":7,"done":true,"total":3,"stored":3}}}}}""",
            )
            val prefs = store().get(a)
            assertEquals(true, prefs.autoFileRequested)
            assertEquals(FolderBackfill(uidValidity = 7, done = true, total = 3, stored = 3), prefs.backfill["Sent"])
            assertEquals(listOf(CustomFolder("Partners", "resellers", enabled = true)), prefs.customFolders)
            assertEquals(true, prefs.alertUrgent && prefs.alertHigh)
            assertEquals(true, reopen().digest())
        }

    @Test
    fun `the digest switch is app-wide and survives a restart`() =
        runBlocking {
            store().setDigest(false)
            assertEquals(false, reopen().digest())
        }

    @Test
    fun `stored folder switches become per-folder auto-file switches`() {
        val auto =
            tech.elephantclock.privatemail.core.data.triage.AutoFile(
                enabled = true,
                disabledRoles = setOf(FolderRole.MONEY),
                disabledCustom = setOf("Partners"),
            )
        val money = Verdict.file(FolderTarget(FolderRole.MONEY), Importance.NORMAL, VerdictSource.RULE, "r")
        val work = Verdict.file(FolderTarget(FolderRole.WORK), Importance.NORMAL, VerdictSource.RULE, "r")
        val partners = Verdict.file(FolderTarget.custom("partners"), Importance.NORMAL, VerdictSource.RULE, "r")
        assertEquals(false, auto.allows(money))
        assertEquals(true, auto.allows(work))
        assertEquals(false, auto.allows(partners))
        assertEquals(true, auto.allows(Verdict.discard(VerdictSource.RULE, "r")))
        assertEquals(true, auto.allows(Verdict.junk(VerdictSource.RULE, "r")))
        assertEquals(false, auto.copy(enabled = false).allows(work))
    }
}

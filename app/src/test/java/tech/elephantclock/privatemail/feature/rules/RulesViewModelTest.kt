package tech.elephantclock.privatemail.feature.rules

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.CustomFolder
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.data.settings.FolderNameProblem
import tech.elephantclock.privatemail.core.data.settings.ImportProblem
import tech.elephantclock.privatemail.core.data.settings.RulesImport
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val data = FakeRulesData()
    private val files = FakeRulesFiles()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = RulesViewModel(data, files, main)

    private fun prefsA() = data.prefs.value[ACCOUNT_A] ?: AccountPrefs()

    @Test
    fun `shows the first account, and each account's own settings and rules`() {
        data.rules.value = mapOf(ACCOUNT_B to listOf(RuleRow(1, "x@y.example", FolderTarget.Discarded, false, false)))
        data.prefs.value = mapOf(ACCOUNT_B to AccountPrefs(alertHigh = false))
        val vm = vm()
        assertEquals(ACCOUNT_A, vm.state.value.selected)
        assertFalse(vm.state.value.loading)
        assertTrue(
            vm.state.value.rules
                .isEmpty(),
        )
        vm.onSelectAccount(ACCOUNT_B)
        assertEquals(1, vm.state.value.rules.size)
        assertFalse(vm.state.value.prefs.alertHigh)
        vm.onAlertHigh(true)
        assertTrue(
            data.prefs.value
                .getValue(ACCOUNT_B)
                .alertHigh,
        )
        assertNull("the other account is untouched", data.prefs.value[ACCOUNT_A])
    }

    @Test
    fun `folder switches are per folder, and Priority can't be switched off`() {
        val vm = vm()
        vm.onToggleFolder(FolderRole.MONEY)
        vm.onToggleFolder(FolderRole.PRIORITY)
        assertEquals(setOf(FolderRole.MONEY), prefsA().disabledFolders)
        vm.onToggleFolder(FolderRole.MONEY)
        assertTrue(prefsA().disabledFolders.isEmpty())
    }

    @Test
    fun `descriptions are edited, and an empty one goes back to the default`() {
        val vm = vm()
        vm.onEditFolder(FolderTarget(FolderRole.WORK))
        vm.onDialogChange(RulesDialog.EditFolder(FolderTarget(FolderRole.WORK), "Bitbucket, Jira, App Store Connect"))
        vm.onSaveDialog()
        assertEquals("Bitbucket, Jira, App Store Connect", prefsA().folderDescriptions[FolderRole.WORK])
        assertNull(vm.state.value.dialog)
        vm.onEditFolder(FolderTarget(FolderRole.WORK))
        vm.onDialogChange(RulesDialog.EditFolder(FolderTarget(FolderRole.WORK), "  "))
        vm.onSaveDialog()
        assertNull(prefsA().folderDescriptions[FolderRole.WORK])
    }

    @Test
    fun `custom folders are added with checked names, switched and removed`() {
        val vm = vm()
        vm.onAddFolder()
        vm.onDialogChange(RulesDialog.AddFolder("Work/Secret", "x"))
        vm.onSaveDialog()
        assertEquals(FolderNameProblem.BAD_CHARACTER, (vm.state.value.dialog as RulesDialog.AddFolder).problem)
        vm.onDialogChange(RulesDialog.AddFolder("Trash", "x"))
        vm.onSaveDialog()
        assertEquals(FolderNameProblem.RESERVED, (vm.state.value.dialog as RulesDialog.AddFolder).problem)
        vm.onDialogChange(RulesDialog.AddFolder("Partners", ""))
        vm.onSaveDialog()
        assertEquals(FolderNameProblem.EMPTY, (vm.state.value.dialog as RulesDialog.AddFolder).problem)
        vm.onDialogChange(RulesDialog.AddFolder(" Partners ", "Integrations, resellers"))
        vm.onSaveDialog()
        assertEquals(listOf(CustomFolder("Partners", "Integrations, resellers")), prefsA().customFolders)
        assertEquals(
            FolderTarget.custom("Partners"),
            vm.state.value.ruleTargets
                .first { it.role == FolderRole.CUSTOM },
        )

        vm.onToggleCustom("Partners")
        assertFalse(prefsA().customFolders.single().enabled)
        vm.onRemoveCustom("Partners")
        assertTrue(prefsA().customFolders.isEmpty())
    }

    @Test
    fun `rules are retargeted and deleted, a VIP rule is never retargeted`() {
        val plain = RuleRow(1, "appfigures.com", FolderTarget(FolderRole.REPORTS), false, false)
        val vip = RuleRow(2, "us@informap.example", FolderTarget.Priority, true, false)
        data.rules.value = mapOf(ACCOUNT_A to listOf(plain, vip))
        val vm = vm()
        vm.onRuleClick(plain)
        assertEquals(RulesDialog.Retarget(plain), vm.state.value.dialog)
        vm.onRetarget(plain, FolderTarget.Discarded)
        assertEquals(listOf(1L to FolderTarget.Discarded), data.retargets)
        vm.onRetarget(vip, FolderTarget.Discarded)
        assertEquals(1, data.retargets.size)
        assertEquals(RulesNotice.VipRuleFixed, vm.state.value.notice)
        vm.onDeleteRule(vip)
        assertEquals(listOf(2L), data.deleted)
    }

    @Test
    fun `alerts, digest, discard mode and the missing-Trash note`() {
        data.trash.value = false
        val vm = vm()
        vm.onAlertUrgent(false)
        vm.onDigest(false)
        assertFalse(prefsA().alertUrgent)
        assertFalse(data.digest.value)
        assertFalse(vm.state.value.trashMissing)
        vm.onDiscardMode(DiscardMode.STRAIGHT_TO_TRASH)
        assertEquals(DiscardMode.STRAIGHT_TO_TRASH, prefsA().discardMode)
        assertTrue(vm.state.value.trashMissing)
    }

    @Test
    fun `auto-file state follows the request and the Sent backfill gate`() {
        data.gate = BackfillStatus.PENDING
        val vm = vm()
        assertEquals(AutoFileState.NOT_REQUESTED, vm.state.value.autoFile)
        data.prefs.value = mapOf(ACCOUNT_A to AccountPrefs(autoFileRequested = true))
        assertEquals(AutoFileState.REREADING_SENT, vm.state.value.autoFile)
        data.gate = BackfillStatus.COMPLETE
        data.prefs.value = mapOf(ACCOUNT_A to AccountPrefs(autoFileRequested = true, alertHigh = false))
        assertEquals(AutoFileState.ON, vm.state.value.autoFile)
    }

    @Test
    fun `authserv-ids are added lower-case, checked and capped`() {
        val vm = vm()
        vm.onAuthservDraft(" MX.OurCo.example ")
        vm.onAddAuthserv()
        vm.onAuthservDraft("mx; dkim=pass")
        vm.onAddAuthserv()
        assertEquals(setOf("mx.ourco.example"), prefsA().trustedAuthservIds)
        repeat(20) {
            vm.onAuthservDraft("mx$it.example")
            vm.onAddAuthserv()
        }
        assertEquals(RulesImport.MAX_AUTHSERV_IDS, prefsA().trustedAuthservIds.size)
        vm.onRemoveAuthserv("mx.ourco.example")
        assertFalse("mx.ourco.example" in prefsA().trustedAuthservIds)
    }

    @Test
    fun `export writes the file, import asks first and trusts authserv-ids only when ticked`() {
        val vm = vm()
        vm.onExportTo("content://doc/1")
        assertEquals("{}", files.written.getValue("content://doc/1").decodeToString())
        assertEquals(RulesNotice.Exported, vm.state.value.notice)
        files.failWrites = true
        vm.onExportTo("content://doc/2")
        assertEquals(RulesNotice.ExportFailed, vm.state.value.notice)

        files.documents["content://in"] =
            (
                """{"format":"private-email-agent/rules","version":1,""" +
                    """"trustedAuthservIds":["mx.x.example"]}"""
            ).encodeToByteArray()
        vm.onImportFrom("content://in")
        val confirm = vm.state.value.dialog as RulesDialog.ConfirmImport
        assertTrue(data.imports.isEmpty())
        vm.onSaveDialog()
        assertEquals(false, data.imports.single().third)
        assertEquals(ACCOUNT_A, data.imports.single().second)

        vm.onImportFrom("content://in")
        vm.onDialogChange(confirm.copy(trustAuthservIds = true))
        vm.onSaveDialog()
        assertEquals(true, data.imports.last().third)
        assertEquals(RulesNotice.Imported, vm.state.value.notice)
    }

    @Test
    fun `hostile or unreadable files are refused with a notice and nothing is applied`() {
        val vm = vm()
        files.documents["big"] = ByteArray(RulesImport.MAX_BYTES * 4) { 'a'.code.toByte() }
        vm.onImportFrom("big")
        assertEquals(RulesNotice.ImportRefused(ImportProblem.TOO_LARGE), vm.state.value.notice)
        files.documents["bad"] =
            """{"format":"private-email-agent/rules","version":1,"backfill":{}}""".encodeToByteArray()
        vm.onImportFrom("bad")
        assertEquals(RulesNotice.ImportRefused(ImportProblem.NOT_JSON), vm.state.value.notice)
        vm.onImportFrom("missing")
        assertEquals(RulesNotice.ImportUnreadable, vm.state.value.notice)
        assertTrue(data.imports.isEmpty())
        assertNull(vm.state.value.dialog)
    }
}

package tech.elephantclock.privatemail.feature.rules

import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.data.settings.FolderNameProblem
import tech.elephantclock.privatemail.core.data.settings.ImportPlan
import tech.elephantclock.privatemail.core.data.settings.ImportProblem
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget

/** One account chip in the header. */
data class RulesAccount(
    val id: AccountId,
    val label: String,
)

/** One learned rule as screen 07 lists it: "who → where". */
data class RuleRow(
    val id: Long,
    val who: String,
    val target: FolderTarget,
    val vip: Boolean,
    /** A rule for every account (shown with a tag; editing it changes it everywhere). */
    val allAccounts: Boolean,
)

/** Where auto-file stands for the selected account, from the request and the Sent backfill gate. */
enum class AutoFileState {
    /** Apply hasn't happened yet: label only. */
    NOT_REQUESTED,

    /** Requested and the Sent history is complete: mail moves. */
    ON,

    /** Requested, but the Sent history must be read again (UIDVALIDITY reset, new Sent folder). */
    REREADING_SENT,

    /** Requested, but the account has no Sent folder, so nobody can be protected. */
    NO_SENT_FOLDER,
    ;

    companion object {
        fun of(
            requested: Boolean,
            gate: BackfillStatus?,
        ): AutoFileState =
            when {
                !requested -> NOT_REQUESTED
                gate == BackfillStatus.COMPLETE -> ON
                gate == BackfillStatus.NO_SENT_FOLDER -> NO_SENT_FOLDER
                else -> REREADING_SENT
            }
    }
}

/** The dialog open on top of the screen, if any. */
sealed interface RulesDialog {
    /** Edit a built-in folder's description ([target] Priority or a value folder) or a custom folder's. */
    data class EditFolder(
        val target: FolderTarget,
        val draft: String,
        val problem: FolderNameProblem? = null,
    ) : RulesDialog

    data class AddFolder(
        val name: String = "",
        val description: String = "",
        val problem: FolderNameProblem? = null,
    ) : RulesDialog

    data class Retarget(
        val rule: RuleRow,
    ) : RulesDialog

    /** A checked import, waiting for the user's OK; [trustAuthservIds] is an explicit extra choice. */
    data class ConfirmImport(
        val plan: ImportPlan,
        val trustAuthservIds: Boolean = false,
    ) : RulesDialog
}

/** A one-off message for the snackbar. */
sealed interface RulesNotice {
    data object Exported : RulesNotice

    data object ExportFailed : RulesNotice

    data object Imported : RulesNotice

    data class ImportRefused(
        val problem: ImportProblem,
    ) : RulesNotice

    data object ImportUnreadable : RulesNotice

    data object VipRuleFixed : RulesNotice

    data object RuleGone : RulesNotice
}

data class RulesUiState(
    val loading: Boolean = true,
    val accounts: List<RulesAccount> = emptyList(),
    val selected: AccountId? = null,
    val prefs: AccountPrefs = AccountPrefs(),
    val rules: List<RuleRow> = emptyList(),
    val digest: Boolean = true,
    val gate: BackfillStatus? = null,
    val hasTrash: Boolean = true,
    val authservDraft: String = "",
    val showAdvanced: Boolean = false,
    val dialog: RulesDialog? = null,
    val notice: RulesNotice? = null,
) {
    val autoFile: AutoFileState get() = AutoFileState.of(prefs.autoFileRequested, gate)

    /** "Straight to Trash" was chosen but the server has no Trash: the mail is held in Discarded instead. */
    val trashMissing: Boolean get() = prefs.discardMode == DiscardMode.STRAIGHT_TO_TRASH && !hasTrash

    /** Folders a rule can be pointed at: Priority, the value folders, the custom folders, Discarded, Junk. */
    val ruleTargets: List<FolderTarget>
        get() =
            listOf(FolderTarget.Priority) + VALUE_ROLES.map { FolderTarget(it) } +
                prefs.customFolders.map { FolderTarget.custom(it.name) } +
                listOf(FolderTarget.Discarded, FolderTarget.Junk)

    private companion object {
        val VALUE_ROLES =
            listOf(FolderRole.PEOPLE, FolderRole.MONEY, FolderRole.WORK, FolderRole.ORDERS, FolderRole.REPORTS)
    }
}

interface RulesActions {
    fun onSelectAccount(id: AccountId) = Unit

    fun onToggleFolder(role: FolderRole) = Unit

    fun onToggleCustom(name: String) = Unit

    fun onEditFolder(target: FolderTarget) = Unit

    fun onAddFolder() = Unit

    fun onDialogChange(dialog: RulesDialog) = Unit

    fun onSaveDialog() = Unit

    fun onDismissDialog() = Unit

    fun onRemoveCustom(name: String) = Unit

    fun onRuleClick(rule: RuleRow) = Unit

    fun onRetarget(
        rule: RuleRow,
        target: FolderTarget,
    ) = Unit

    fun onDeleteRule(rule: RuleRow) = Unit

    fun onAlertUrgent(on: Boolean) = Unit

    fun onAlertHigh(on: Boolean) = Unit

    fun onDigest(on: Boolean) = Unit

    fun onDiscardMode(mode: DiscardMode) = Unit

    fun onToggleAdvanced() = Unit

    fun onAuthservDraft(text: String) = Unit

    fun onAddAuthserv() = Unit

    fun onRemoveAuthserv(id: String) = Unit

    /** The system file picker returned these documents (content URIs as strings). */
    fun onExportTo(uri: String) = Unit

    fun onImportFrom(uri: String) = Unit

    fun onNoticeShown() = Unit

    object NoActions : RulesActions
}

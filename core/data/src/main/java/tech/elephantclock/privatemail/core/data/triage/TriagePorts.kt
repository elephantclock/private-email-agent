package tech.elephantclock.privatemail.core.data.triage

import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.rules.Decision
import java.util.concurrent.ConcurrentHashMap

/**
 * The on-device model, as triage sees it: classifies a message the rules couldn't decide.
 * `core:ai`'s engine is adapted to this in the app. Returns null when the model is unavailable
 * (no Gemini Nano, still downloading, busy, failed): the message then stays in Priority,
 * rules-only. Whatever it returns goes through [Decision.NeedsAi.accept] and the protections.
 */
fun interface TriageAi {
    suspend fun classify(
        message: MailItem,
        decision: Decision.NeedsAi,
    ): Verdict?

    companion object {
        val UNAVAILABLE = TriageAi { _, _ -> null }
    }
}

/**
 * The user's auto-file switches. Until the user has applied the first run ([enabled] false,
 * the default) triage only labels: verdicts are stored, nothing moves on the server.
 *
 * Filing is switched per folder (T48): [disabledRoles] and [disabledCustom] (custom folder
 * names, case-insensitive) keep their mail in the Inbox while every other folder still files.
 * [file] false switches all filing off. Discard and junk have their own switches and are not
 * affected by the folder switches.
 */
data class AutoFile(
    val enabled: Boolean = false,
    val file: Boolean = true,
    val discard: Boolean = true,
    val junk: Boolean = true,
    val disabledRoles: Set<FolderRole> = emptySet(),
    val disabledCustom: Set<String> = emptySet(),
) {
    /** Whether triage may move a message for [action] to some folder (KEEP never moves). */
    fun allows(action: VerdictAction): Boolean =
        enabled &&
            when (action) {
                VerdictAction.KEEP -> false
                VerdictAction.FILE -> file
                VerdictAction.DISCARD -> discard
                VerdictAction.JUNK -> junk
            }

    /** Whether triage may carry out [verdict]: its action is allowed and, when filing, its folder is on. */
    fun allows(verdict: Verdict): Boolean =
        allows(verdict.action) && (verdict.action != VerdictAction.FILE || allowsFolder(verdict.target))

    fun allowsFolder(target: FolderTarget): Boolean =
        when (target.role) {
            FolderRole.CUSTOM -> disabledCustom.none { it.equals(target.customName, ignoreCase = true) }
            else -> target.role !in disabledRoles
        }

    companion object {
        val LABEL_ONLY = AutoFile()
        val ALL = AutoFile(enabled = true)
    }
}

/**
 * A folder triage may file into and the user's plain-language description of it, for the
 * on-device model (T31 reads [AccountTriageSettings.folders]). [enabled] false: switched off.
 */
data class FolderGuide(
    val target: FolderTarget,
    val description: String,
    val enabled: Boolean = true,
)

/** Per-account triage settings (T42/T48 edit them; the app backs them with DataStore). */
data class AccountTriageSettings(
    val autoFile: AutoFile = AutoFile.LABEL_ONLY,
    /** The user's server's authserv-ids; only their `Authentication-Results` are trusted. */
    val trustedAuthservIds: Set<String> = emptySet(),
    /** VIP addresses (also learned VIP rules); always Priority, URGENT. */
    val vips: Set<String> = emptySet(),
    /** Aliases of the account's own address: never "sent to", and forged "from me" mail isn't protected. */
    val ownAddresses: Set<String> = emptySet(),
    val notifications: Boolean = true,
    /** Which importances alert (screen 07's Urgent and High switches); only URGENT and HIGH ever do. */
    val alertLevels: Set<Importance> = setOf(Importance.URGENT, Importance.HIGH),
    /**
     * Priority, the value folders and the user's custom folders with their descriptions, in the
     * order screen 07 shows them. Empty when the settings don't come from the store (tests).
     */
    val folders: List<FolderGuide> = emptyList(),
) {
    /** The user's custom folders (on or off), for the AI (T31). */
    val customFolders: List<FolderGuide> get() = folders.filter { it.target.role == FolderRole.CUSTOM }
}

fun interface TriageSettings {
    suspend fun forAccount(id: AccountId): AccountTriageSettings

    companion object {
        /** Label only, everything else default. */
        val DEFAULTS = TriageSettings { AccountTriageSettings() }
    }
}

/** Settings held in memory; for tests, and for the app until its DataStore-backed version exists. */
class InMemoryTriageSettings(
    private val default: AccountTriageSettings = AccountTriageSettings(),
) : TriageSettings {
    private val byAccount = ConcurrentHashMap<AccountId, AccountTriageSettings>()

    fun set(
        id: AccountId,
        settings: AccountTriageSettings,
    ) {
        byAccount[id] = settings
    }

    override suspend fun forAccount(id: AccountId): AccountTriageSettings = byAccount[id] ?: default
}

/** A message worth a notification: URGENT or HIGH, new, unread and still in the Inbox. */
data class TriageAlert(
    val accountId: AccountId,
    val localId: Long,
    val from: Address?,
    val subject: String,
    val importance: Importance,
    val reason: String,
)

/** Posts notifications (the app, T49). Called after the verdict is stored. */
fun interface TriageNotifier {
    fun notify(alert: TriageAlert)

    companion object {
        val NONE = TriageNotifier { }
    }
}

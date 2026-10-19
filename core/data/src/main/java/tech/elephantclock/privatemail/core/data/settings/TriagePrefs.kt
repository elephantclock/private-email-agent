package tech.elephantclock.privatemail.core.data.settings

import kotlinx.serialization.Serializable
import tech.elephantclock.privatemail.core.model.FolderRole

/** What happens to discarded mail (screen 02). */
@Serializable
enum class DiscardMode {
    /** Held in Discarded for 30 days, then moved to Trash (recommended). */
    HOLD_30_DAYS,

    /**
     * Straight to the server's special-use Trash (`TriageMover`). Trash is never created: on a
     * server without one, discarded mail is held in Discarded as in [HOLD_30_DAYS].
     */
    STRAIGHT_TO_TRASH,
}

/** Where an account is in onboarding: Add account (01) → What matters (02) → First run (03) → done. */
@Serializable
enum class OnboardingStep { PERSONALISE, FIRST_RUN, DONE }

/**
 * A folder the user described in plain words; the AI (T31) reads [description] when filing.
 * It is created on the server the first time triage files into it. [enabled] false: nothing is
 * filed there (its mail stays in the Inbox), like a switched-off value folder.
 */
@Serializable
data class CustomFolder(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
)

/** Progress of the Sent-history backfill for one Sent folder (see `SentHistoryBackfill`). */
@Serializable
data class FolderBackfill(
    val uidValidity: Long,
    /** Everything in the window at or above this UID is stored; null before the first page. */
    val belowUid: Long? = null,
    val done: Boolean = false,
    val total: Int = 0,
    val stored: Int = 0,
)

/** The value folders screen 02 offers, in the mockup's order. */
val VALUE_FOLDERS: List<FolderRole> =
    listOf(FolderRole.PEOPLE, FolderRole.MONEY, FolderRole.WORK, FolderRole.ORDERS, FolderRole.REPORTS)

/** Everything the app remembers about one account's triage. Defaults are the safe ones. */
@Serializable
data class AccountPrefs(
    val step: OnboardingStep = OnboardingStep.PERSONALISE,
    val vips: Set<String> = emptySet(),
    /** Value folders the user switched off: their mail stays in the Inbox (per role, T48). */
    val disabledFolders: Set<FolderRole> = emptySet(),
    /** The user's own words for a built-in folder (Priority and the value folders); else the default. */
    val folderDescriptions: Map<FolderRole, String> = emptyMap(),
    val customFolders: List<CustomFolder> = emptyList(),
    val discardMode: DiscardMode = DiscardMode.HOLD_30_DAYS,
    /**
     * The user asked for auto-file (Apply on screen 03, later the rules screen). It takes effect
     * only once the Sent backfill is complete; see `StoredTriageSettings`.
     */
    val autoFileRequested: Boolean = false,
    val notifications: Boolean = true,
    /** Alert levels (screen 07): URGENT alerts with sound, HIGH silently; everything else never alerts. */
    val alertUrgent: Boolean = true,
    val alertHigh: Boolean = true,
    val trustedAuthservIds: Set<String> = emptySet(),
    val ownAddresses: Set<String> = emptySet(),
    /** Sent folder path → backfill progress. */
    val backfill: Map<String, FolderBackfill> = emptyMap(),
) {
    fun folderEnabled(role: FolderRole): Boolean = role !in disabledFolders
}

/**
 * The whole file: one entry per account id, plus app-wide switches. Every field has a default:
 * a file written by an older version must still decode, or the corruption handler would reset
 * it and wipe the backfill cursors (closing the auto-file gate).
 */
@Serializable
data class TriagePrefs(
    val accounts: Map<String, AccountPrefs> = emptyMap(),
    /** The 18:00 daily digest (all accounts together, so one switch). */
    val digest: Boolean = true,
)

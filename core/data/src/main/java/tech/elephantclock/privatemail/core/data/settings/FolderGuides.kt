package tech.elephantclock.privatemail.core.data.settings

import tech.elephantclock.privatemail.core.data.triage.FolderGuide
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget

/**
 * The built-in folders' plain-language descriptions, as the on-device model reads them (T31)
 * when the user hasn't written their own. English on purpose: they are prompt text, not UI.
 */
val DEFAULT_FOLDER_DESCRIPTIONS: Map<FolderRole, String> =
    mapOf(
        FolderRole.PRIORITY to "Money at risk, outages, security, deadlines, anyone waiting on me.",
        FolderRole.PEOPLE to "Real conversations with people, not automated mail.",
        FolderRole.MONEY to "Invoices, payments, receipts and bank mail.",
        FolderRole.WORK to "Code reviews, tickets, builds, calendars and other work tools.",
        FolderRole.ORDERS to "Bookings, orders, deliveries and travel.",
        FolderRole.REPORTS to "Automated reports and alerts that are still useful.",
    )

/** The folders screen 07 lists, in order: Priority, then the value folders. */
val DESCRIBED_FOLDERS: List<FolderRole> = listOf(FolderRole.PRIORITY) + VALUE_FOLDERS

/** Priority, the value folders and the custom folders with their descriptions (on or off). */
fun AccountPrefs.folderGuides(): List<FolderGuide> =
    DESCRIBED_FOLDERS.map { role ->
        FolderGuide(
            target = FolderTarget(role),
            description = folderDescriptions[role] ?: DEFAULT_FOLDER_DESCRIPTIONS.getValue(role),
            enabled = role == FolderRole.PRIORITY || folderEnabled(role),
        )
    } + customFolders.map { FolderGuide(FolderTarget.custom(it.name), it.description, it.enabled) }

/** Why a custom folder name or a description can't be used. */
enum class FolderNameProblem { EMPTY, TOO_LONG, BAD_CHARACTER, DUPLICATE, RESERVED }

/**
 * Checks for custom folder names and descriptions, shared by the rules screen and the import.
 * Names become IMAP folder names under the personal namespace, so hierarchy separators (`/`,
 * `.`), LIST wildcards (`%`, `*`), quotes, backslashes and control characters are refused, as
 * are names triage or the server already use.
 */
object FolderNames {
    const val MAX_NAME = 40
    const val MAX_DESCRIPTION = 300
    const val MAX_CUSTOM_FOLDERS = 20

    private const val FORBIDDEN = "/.%*\\\"&"

    /** Names triage already uses (the app's folder labels and the server's own folders). */
    val RESERVED: List<String> =
        listOf(
            "Inbox",
            "Priority",
            "People",
            "Money",
            "Work",
            "Work updates",
            "Orders",
            "Orders & travel",
            "Reports",
            "Reports & alerts",
            "Discarded",
            "Junk",
            "Spam",
            "Sent",
            "Trash",
            "Drafts",
            "Archive",
        )

    fun checkName(
        name: String,
        existing: Collection<String>,
    ): FolderNameProblem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> {
                FolderNameProblem.EMPTY
            }

            trimmed.length > MAX_NAME -> {
                FolderNameProblem.TOO_LONG
            }

            trimmed.any {
                it in FORBIDDEN || it.isISOControl() || Character.getType(
                    it,
                ) == Character.FORMAT.toInt()
            } -> {
                FolderNameProblem.BAD_CHARACTER
            }

            RESERVED.any { it.equals(trimmed, ignoreCase = true) } -> {
                FolderNameProblem.RESERVED
            }

            existing.any { it.equals(trimmed, ignoreCase = true) } -> {
                FolderNameProblem.DUPLICATE
            }

            else -> {
                null
            }
        }
    }

    fun checkDescription(text: String): FolderNameProblem? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> FolderNameProblem.EMPTY
            trimmed.length > MAX_DESCRIPTION -> FolderNameProblem.TOO_LONG
            trimmed.any { it.isISOControl() && it != '\n' } -> FolderNameProblem.BAD_CHARACTER
            else -> null
        }
    }
}

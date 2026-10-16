package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable

/** An IMAP folder in one account. */
@Serializable
data class MailFolder(
    val accountId: AccountId,
    /** Full IMAP name, e.g. "INBOX" or "Work updates". */
    val path: String,
    val displayName: String,
    val uidValidity: Long,
    val specialUse: SpecialUse? = null,
    /** Set when the folder is a triage destination. */
    val role: FolderRole? = null,
    /** Plain-language description the AI reads when choosing a folder. */
    val description: String? = null,
) {
    init {
        require(path.isNotBlank()) { "path must not be blank" }
        require(uidValidity > 0) { "uidValidity must be positive" }
    }
}

/** A triage destination: a role, plus the folder name when the role is [FolderRole.CUSTOM]. */
@Serializable
data class FolderTarget(
    val role: FolderRole,
    val customName: String? = null,
) {
    init {
        require((role == FolderRole.CUSTOM) == (customName != null)) {
            "customName is required for CUSTOM and not allowed otherwise"
        }
        require(customName == null || customName.isNotBlank()) { "customName must not be blank" }
    }

    companion object {
        val Priority = FolderTarget(FolderRole.PRIORITY)
        val Discarded = FolderTarget(FolderRole.DISCARDED)
        val Junk = FolderTarget(FolderRole.JUNK)

        fun custom(name: String) = FolderTarget(FolderRole.CUSTOM, name)
    }
}

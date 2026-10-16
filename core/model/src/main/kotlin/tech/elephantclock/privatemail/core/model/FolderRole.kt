package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable

/** What a folder means to triage. Every verdict targets exactly one role. */
@Serializable
enum class FolderRole {
    /** The Inbox: mail that needs the user. */
    PRIORITY,
    PEOPLE,
    MONEY,
    WORK,
    ORDERS,
    REPORTS,

    /** A folder the user described in plain words. */
    CUSTOM,

    /** Held for 30 days, then moved to Trash. */
    DISCARDED,
    JUNK,
    ;

    /** Roles that take mail away from the user's attention. */
    val isDiscard: Boolean get() = this == DISCARDED || this == JUNK
}

/** Server-side meaning of a folder (RFC 6154 special-use), independent of triage. */
@Serializable
enum class SpecialUse { INBOX, SENT, DRAFTS, TRASH, JUNK, ARCHIVE, ALL }

package tech.elephantclock.privatemail.core.mail.sync

import tech.elephantclock.privatemail.core.model.AccountId

/** Where the last sync of one folder stopped. Persisted by the repository (T13/T14). */
data class SyncState(
    val accountId: AccountId,
    val folderPath: String,
    val uidValidity: Long,
    /** Highest UID already fetched; the next sync asks for UIDs above it. */
    val highestUid: Long,
    val lastSyncMillis: Long,
)

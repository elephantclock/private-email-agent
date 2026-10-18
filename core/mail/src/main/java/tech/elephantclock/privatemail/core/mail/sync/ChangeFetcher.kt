package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.FetchProfile
import jakarta.mail.Message
import org.eclipse.angus.mail.imap.IMAPFolder
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.model.MessageFlag

/** What we already store for one folder: the UID range to check and the last mod-sequence seen. */
data class KnownMessages(
    val uidValidity: Long,
    /** Lowest and highest UID stored locally; only this range is asked about. */
    val lowestUid: Long,
    val highestUid: Long,
    /** HIGHESTMODSEQ from the last check (CONDSTORE servers), null if unknown. */
    val highestModSeq: Long?,
) {
    init {
        require(lowestUid in 1..highestUid) { "empty or invalid UID range $lowestUid..$highestUid" }
    }
}

/** A message's current flags on the server. */
data class FlagUpdate(
    val uid: Long,
    val flags: Set<MessageFlag>,
)

/** What other clients changed among the messages we store. */
data class FolderChanges(
    /** UIDs in the checked range that still exist; stored UIDs missing here were expunged. */
    val presentUids: Set<Long>,
    /** Every present message when [incremental] is false; only changed ones when true. */
    val flagUpdates: List<FlagUpdate>,
    /** True when [flagUpdates] came from `CHANGEDSINCE`, so absent UIDs kept their flags. */
    val incremental: Boolean,
    /** To store for next time; null when the server has no CONDSTORE (or reports NOMODSEQ). */
    val highestModSeq: Long?,
)

/**
 * Reconciles what other clients did to messages we already have, read-only (EXAMINE):
 * - Flags: with CONDSTORE and a stored mod-sequence, `UID FETCH lo:hi (FLAGS) (CHANGEDSINCE m)`
 *   returns only what changed. Otherwise every message's flags in the stored range are fetched;
 *   this runs on each sync (the worker's 15 minutes, or an IDLE wake-up), which is the
 *   "periodic flag fetch".
 * - Expunges: `UID FETCH lo:hi (UID)` lists the UIDs still there; the caller deletes the rest.
 *   QRESYNC/VANISHED isn't used (see the T14 Log): the range is bounded by what we store
 *   (about 30 days of mail), so the list is small.
 *
 * Returns null when the folder's UIDVALIDITY no longer matches [KnownMessages.uidValidity]:
 * the UIDs mean different messages now, and comparing them would delete or re-flag the wrong ones.
 */
class ChangeFetcher {
    fun fetch(
        connection: ImapConnection,
        folderPath: String,
        known: KnownMessages,
    ): FolderChanges? {
        val condStore = connection.condStoreUsable
        val folder = connection.openReadOnly(folderPath, condStore)
        try {
            if (folder.uidValidity != known.uidValidity) return null
            // Read before fetching (like UIDNEXT in HeaderFetcher): a change made during the
            // fetch gets a higher mod-sequence and is picked up next time.
            val modSeq = if (condStore) folder.highestModSeq.takeIf { it > 0 } else null
            val present = folder.getMessagesByUID(known.lowestUid, known.highestUid).filterNotNull()
            val presentUids = present.map { folder.getUID(it) }.filter { it in known.lowestUid..known.highestUid }
            val since = known.highestModSeq
            val incremental = modSeq != null && since != null && since <= modSeq
            val updates =
                when {
                    incremental && since == modSeq -> emptyList()
                    incremental -> changedSince(folder, known, since)
                    else -> allFlags(folder, present)
                }
            return FolderChanges(presentUids.toSet(), updates, incremental, modSeq)
        } finally {
            folder.close(false)
        }
    }

    private fun changedSince(
        folder: IMAPFolder,
        known: KnownMessages,
        since: Long,
    ): List<FlagUpdate> =
        folder
            .getMessagesByUIDChangedSince(known.lowestUid, known.highestUid, since)
            .filterNotNull()
            .map { FlagUpdate(folder.getUID(it), it.flags.toModel()) }
            .filter { it.uid in known.lowestUid..known.highestUid }

    private fun allFlags(
        folder: IMAPFolder,
        present: List<Message>,
    ): List<FlagUpdate> {
        if (present.isEmpty()) return emptyList()
        return present.chunked(BATCH).flatMap { chunk ->
            val array = chunk.toTypedArray()
            folder.fetch(array, FLAGS)
            array.map { FlagUpdate(folder.getUID(it), it.flags.toModel()) }
        }
    }

    private companion object {
        const val BATCH = 500
        val FLAGS = FetchProfile().apply { add(FetchProfile.Item.FLAGS) }
    }
}

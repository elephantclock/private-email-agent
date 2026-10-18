package tech.elephantclock.privatemail.core.mail.ops

import tech.elephantclock.privatemail.core.mail.imap.SelectedFolder
import tech.elephantclock.privatemail.core.mail.imap.UidCommands

/** A message back in its original folder; [uid] is its UID there now, when known. */
data class RestoredMessage(
    val entry: UndoEntry,
    val uid: Long?,
)

data class UndoReport(
    val restored: List<RestoredMessage>,
    /** No copy we could identify with certainty: left alone. */
    val notFound: List<UndoEntry>,
    /** Older than 7 days: not undone. */
    val expired: List<UndoEntry>,
    val failure: TransferFailure?,
) {
    companion object {
        fun merge(
            parts: List<UndoReport>,
            expired: List<UndoEntry>,
        ) = UndoReport(
            parts.flatMap { it.restored },
            parts.flatMap { it.notFound },
            expired + parts.flatMap { it.expired },
            parts.firstNotNullOfOrNull { it.failure },
        )
    }
}

/**
 * Undoes the entries of one (from, to) folder pair:
 * 1. originals still in the source (no MOVE, no UIDPLUS: only marked) get `\Deleted` removed,
 *    and their copies in the destination are removed again by exact UID;
 * 2. every other copy is found (its UID if UIDVALIDITY and Message-ID still match, else the one
 *    message with that Message-ID) and moved back; a copy we can't pin down is left alone.
 */
internal class Undoer(
    private val mailbox: Mailbox,
) {
    private data class Located(
        val entry: UndoEntry,
        val uid: Long?,
    )

    suspend fun undo(group: List<UndoEntry>): UndoReport {
        val from = group.first().fromFolder
        val to = group.first().toFolder
        require(group.all { it.fromFolder == from && it.toFolder == to }) { "one folder pair per call" }

        val inPlace = unmarkOriginals(from, group)
        val (located, destValidity) =
            mailbox.open(to, readWrite = false).use { dst ->
                group.map { Located(it, locate(dst, it)) } to dst.uidValidity
            }

        val duplicates = located.filter { it.entry in inPlace }.mapNotNull { it.uid }
        if (duplicates.isNotEmpty()) removeExactly(to, destValidity, duplicates)

        val toMove = located.filter { it.entry !in inPlace && it.uid != null }
        val notFound = located.filter { it.entry !in inPlace && it.uid == null }.map { it.entry }.toMutableList()
        val restored = inPlace.map { RestoredMessage(it, it.fromUid) }.toMutableList()
        var failure: TransferFailure? = null
        if (toMove.isNotEmpty()) {
            val entryByUid = toMove.associate { it.uid!! to it.entry }
            val items = toMove.map { MoveItem(it.uid!!, it.entry.messageIdHeader, it.entry.localId) }
            val report = Transfer(mailbox).run(to, destValidity, items, from, allowGmailLabels = false) { _, _ -> }
            restored += report.moved.map { RestoredMessage(entryByUid.getValue(it.item.uid), it.newUid) }
            failure = report.failure
            notFound += report.missing.map { entryByUid.getValue(it) }
        }
        return UndoReport(restored, notFound, emptyList(), failure)
    }

    /** Originals that never left the source (marked only): unmark them. Returns their entries. */
    private fun unmarkOriginals(
        from: String,
        group: List<UndoEntry>,
    ): Set<UndoEntry> =
        mailbox.open(from, readWrite = true).use { src ->
            val sameEpoch = group.filter { it.fromUidValidity == src.uidValidity }
            if (sameEpoch.isEmpty()) return@use emptySet()
            val present = src.existing(sameEpoch.map { it.fromUid })
            UidCommands.batches(present).forEach { src.storeFlags(it, Transfer.DELETED, add = false) }
            sameEpoch.filter { it.fromUid in present }.toSet()
        }

    private fun locate(
        dst: SelectedFolder,
        entry: UndoEntry,
    ): Long? {
        val id = entry.messageIdHeader?.takeIf { UidCommands.messageId(it) == it }
        val uid = entry.toUid
        if (uid != null && dst.uidValidity == entry.toUidValidity) {
            val same = if (id == null) uid in dst.existing(listOf(uid)) else dst.hasMessageId(uid, id)
            if (same) return uid
        }
        return id?.let { dst.searchMessageId(it).singleOrNull() }
    }

    /** `\Deleted` and (UIDPLUS) `UID EXPUNGE` on exactly [uids]; without UIDPLUS they stay marked. */
    private fun removeExactly(
        path: String,
        expectedUidValidity: Long,
        uids: List<Long>,
    ) = mailbox.open(path, readWrite = true).use { dst ->
        if (dst.uidValidity != expectedUidValidity) return@use
        UidCommands.batches(uids).forEach { batch ->
            dst.storeFlags(batch, Transfer.DELETED, add = true)
            if (mailbox.capabilities.uidPlus) dst.uidExpunge(batch)
        }
    }
}

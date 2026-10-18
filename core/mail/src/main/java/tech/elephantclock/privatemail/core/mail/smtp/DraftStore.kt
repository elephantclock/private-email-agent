package tech.elephantclock.privatemail.core.mail.smtp

import jakarta.mail.Flags
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.FolderPaths
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.imap.UidFolder
import tech.elephantclock.privatemail.core.model.SpecialUse

/** What happened to the previous version of a draft. */
enum class OldDraft {
    /** There was none. */
    NONE,

    /** Removed with `UID EXPUNGE` of exactly its UID. */
    REMOVED,

    /** No UIDPLUS: marked `\Deleted` and left for the user's next expunge (we never expunge blindly). */
    MARKED_DELETED,

    /** Not touched: its folder's UIDVALIDITY changed, its UID is unknown, or it isn't our draft any more. */
    KEPT,

    /** Already gone from the server. */
    GONE,
}

data class DraftSave(
    val stored: StoredMessage,
    val previous: OldDraft,
)

/**
 * Drafts on the server: APPEND to the Drafts folder with `\Draft` and `\Seen`. Replacing a draft
 * stores the new version first and only then removes the old one, by its exact UID, after
 * checking it still is the same draft (same UIDVALIDITY, `\Draft`, same Message-ID).
 */
class DraftStore(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun save(
        connection: ImapConnection,
        message: OutgoingMessage,
        replacing: StoredMessage? = null,
        capabilities: Capabilities = connection.capabilities,
    ): DraftSave {
        val path = replacing?.folderPath ?: FolderPaths.ensureSpecial(connection, SpecialUse.DRAFTS)
        val mime =
            MimeBuilder.build(
                message,
                clock(),
                Flags().apply {
                    add(Flags.Flag.DRAFT)
                    add(Flags.Flag.SEEN)
                },
                strict = false,
            )
        val stored = Appender.append(connection, path, mime, message.messageId, capabilities)
        val previous =
            when {
                replacing == null -> OldDraft.NONE
                else -> delete(connection, replacing, capabilities, keep = setOfNotNull(stored.uid))
            }
        return DraftSave(stored, previous)
    }

    /**
     * Removes [draft] (e.g. after it was sent). Never touches another message: only [draft]'s UID,
     * only if it still has `\Draft` and its Message-ID, and never one of [keep].
     */
    fun delete(
        connection: ImapConnection,
        draft: StoredMessage,
        capabilities: Capabilities = connection.capabilities,
        keep: Set<Long> = emptySet(),
    ): OldDraft =
        UidFolder.withReadWrite(connection, draft.folderPath, capabilities) { f ->
            if (f.uidValidity != draft.uidValidity) return@withReadWrite OldDraft.KEPT
            val uid = draft.uid ?: return@withReadWrite OldDraft.KEPT
            if (uid in keep) return@withReadWrite OldDraft.KEPT
            if (uid !in f.existing(listOf(uid))) return@withReadWrite OldDraft.GONE
            if (!f.hasMessageId(uid, draft.messageId, draft = true)) return@withReadWrite OldDraft.KEPT
            f.storeFlags(listOf(uid), "\\Deleted", add = true)
            if (capabilities.uidPlus) {
                f.uidExpunge(listOf(uid))
                OldDraft.REMOVED
            } else {
                OldDraft.MARKED_DELETED
            }
        }
}

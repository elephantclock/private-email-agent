package tech.elephantclock.privatemail.core.mail.imap

import jakarta.mail.Folder
import jakarta.mail.MessagingException
import org.eclipse.angus.mail.iap.Argument
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.BASE64MailboxEncoder
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import java.nio.charset.StandardCharsets

/**
 * UID commands on one selected folder. Every command names exact UIDs, and there is no way to
 * send a plain `EXPUNGE` or `CLOSE` from here: [uidExpunge] is `UID EXPUNGE <uids>` and needs
 * UIDPLUS. [capabilities] is passed in (not read from the server) so tests can switch MOVE and
 * UIDPLUS off.
 */
internal class UidFolder(
    val folder: IMAPFolder,
    val capabilities: Capabilities,
) {
    val uidValidity: Long get() = folder.uidValidity

    /** Of [uids], those that exist in the folder now. */
    fun existing(uids: Collection<Long>): Set<Long> {
        if (uids.isEmpty()) return emptySet()
        val wanted = uids.toSet()
        return UidCommands
            .batches(uids)
            .flatMap { batch -> UidCommands.parseSearch(run("UID SEARCH UID ${UidCommands.set(batch)}")) }
            .filter { it in wanted }
            .toSet()
    }

    /** `UID MOVE`; needs MOVE. The COPYUID, if the server sent one. */
    fun uidMove(
        uids: Collection<Long>,
        destination: String,
    ): CopyUid? {
        check(capabilities.move) { "server has no MOVE" }
        return copyOrMove("UID MOVE", uids, destination)
    }

    fun uidCopy(
        uids: Collection<Long>,
        destination: String,
    ): CopyUid? = copyOrMove("UID COPY", uids, destination)

    /** `UID STORE <uids> +FLAGS.SILENT (<flags>)` (or `-FLAGS`). */
    fun storeFlags(
        uids: Collection<Long>,
        flags: String,
        add: Boolean,
    ) {
        run("UID STORE ${UidCommands.set(uids)} ${if (add) '+' else '-'}FLAGS.SILENT ($flags)")
    }

    /** Removes exactly [uids] (which must be marked `\Deleted`); nothing else is expunged. */
    fun uidExpunge(uids: Collection<Long>) {
        check(capabilities.uidPlus) { "UID EXPUNGE needs UIDPLUS" }
        run("UID EXPUNGE ${UidCommands.set(uids)}")
    }

    /** UIDs ≥ [minUid] whose Message-ID header is [messageId]. */
    fun searchMessageId(
        messageId: String,
        minUid: Long = 1,
    ): List<Long> =
        // "n:*" also matches the highest UID when that is below n, so filter again.
        searchHeader("UID ${minUid.coerceAtLeast(1)}:*", messageId).filter { it >= minUid }

    /** Whether [uid] exists and carries [messageId] (and the `\Draft` flag when [draft]). */
    fun hasMessageId(
        uid: Long,
        messageId: String,
        draft: Boolean = false,
    ): Boolean = uid in searchHeader("UID $uid${if (draft) " DRAFT" else ""}", messageId)

    private fun searchHeader(
        criteria: String,
        messageId: String,
    ): List<Long> {
        require(messageId.all { it.code in 0x21..0x7e }) { "Message-ID must be printable ASCII" }
        val args = Argument().apply { writeString(messageId) }
        return UidCommands.parseSearch(run("UID SEARCH $criteria HEADER Message-ID", args))
    }

    /** Runs one command; NO or BAD throws [MessagingException]. Returns the raw reply lines. */
    fun run(
        command: String,
        args: Argument? = null,
    ): List<String> {
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { p ->
            val responses = p.command(command, args)
            p.notifyResponseHandlers(responses)
            p.handleResult(responses.last())
            responses.map { it.toString() }
        } as List<String>
    }

    private fun copyOrMove(
        verb: String,
        uids: Collection<Long>,
        destination: String,
    ): CopyUid? {
        val args = mailboxArgument(destination)
        val lines = run("$verb ${UidCommands.set(uids)}", args)
        return if (capabilities.uidPlus) UidCommands.parseCopyUid(lines) else null
    }

    private fun mailboxArgument(name: String): Argument =
        Argument().apply {
            val protocol = folder.doCommand { p -> (p as IMAPProtocol).supportsUtf8() } as Boolean
            if (protocol) writeString(name, StandardCharsets.UTF_8) else writeString(BASE64MailboxEncoder.encode(name))
        }

    companion object {
        /**
         * Opens [path] read-write, runs [block], then closes without expunging (Angus sends
         * UNSELECT, or EXAMINE + CLOSE, neither of which removes anything).
         */
        fun <T> withReadWrite(
            connection: ImapConnection,
            path: String,
            capabilities: Capabilities,
            block: (UidFolder) -> T,
        ): T = withFolder(connection, path, capabilities, Folder.READ_WRITE, block)

        /** EXAMINE: for searches that must not change anything. */
        fun <T> withReadOnly(
            connection: ImapConnection,
            path: String,
            capabilities: Capabilities,
            block: (UidFolder) -> T,
        ): T = withFolder(connection, path, capabilities, Folder.READ_ONLY, block)

        private fun <T> withFolder(
            connection: ImapConnection,
            path: String,
            capabilities: Capabilities,
            mode: Int,
            block: (UidFolder) -> T,
        ): T {
            val folder = connection.imapStore.getFolder(path) as IMAPFolder
            folder.open(mode)
            try {
                return block(UidFolder(folder, capabilities))
            } finally {
                if (folder.isOpen) runCatching { folder.close(false) }
            }
        }
    }
}

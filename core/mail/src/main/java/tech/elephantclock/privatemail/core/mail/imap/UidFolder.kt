package tech.elephantclock.privatemail.core.mail.imap

import jakarta.mail.Folder
import jakarta.mail.MessagingException
import org.eclipse.angus.mail.iap.Argument
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.BASE64MailboxEncoder
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import java.io.Closeable
import java.nio.charset.StandardCharsets

/** One selected folder, as the move engine sees it. [UidFolder] on a server; fakes in tests. */
internal interface SelectedFolder : Closeable {
    val path: String
    val uidValidity: Long

    fun existing(uids: Collection<Long>): Set<Long>

    fun uidMove(
        uids: Collection<Long>,
        destination: String,
    ): CopyUid?

    fun uidCopy(
        uids: Collection<Long>,
        destination: String,
    ): CopyUid?

    fun storeFlags(
        uids: Collection<Long>,
        flags: String,
        add: Boolean,
    )

    fun uidExpunge(uids: Collection<Long>)

    /** Gmail only: add or remove labels (`X-GM-LABELS`). */
    fun storeGmailLabels(
        uids: Collection<Long>,
        labels: List<String>,
        add: Boolean,
    )

    fun searchMessageId(
        messageId: String,
        minUid: Long = 1,
    ): List<Long>

    fun hasMessageId(
        uid: Long,
        messageId: String,
        draft: Boolean = false,
    ): Boolean
}

/**
 * UID commands on one selected folder. Every command names exact UIDs, and there is no way to
 * send a plain `EXPUNGE` or `CLOSE` from here: [uidExpunge] is `UID EXPUNGE <uids>` and needs
 * UIDPLUS. [capabilities] is passed in (not read from the server) so tests can switch MOVE and
 * UIDPLUS off.
 */
internal class UidFolder(
    val folder: IMAPFolder,
    val capabilities: Capabilities,
) : SelectedFolder {
    override val path: String get() = folder.fullName
    override val uidValidity: Long get() = folder.uidValidity

    /** Of [uids], those that exist in the folder now. */
    override fun existing(uids: Collection<Long>): Set<Long> {
        if (uids.isEmpty()) return emptySet()
        val wanted = uids.toSet()
        return UidCommands
            .batches(uids)
            .flatMap { batch -> UidCommands.parseSearch(run("UID SEARCH UID ${UidCommands.set(batch)}")) }
            .filter { it in wanted }
            .toSet()
    }

    /** `UID MOVE`; needs MOVE. The COPYUID, if the server sent one. */
    override fun uidMove(
        uids: Collection<Long>,
        destination: String,
    ): CopyUid? {
        check(capabilities.move) { "server has no MOVE" }
        return copyOrMove("UID MOVE", uids, destination)
    }

    override fun uidCopy(
        uids: Collection<Long>,
        destination: String,
    ): CopyUid? = copyOrMove("UID COPY", uids, destination)

    /** `UID STORE <uids> +FLAGS.SILENT (<flags>)` (or `-FLAGS`). */
    override fun storeFlags(
        uids: Collection<Long>,
        flags: String,
        add: Boolean,
    ) {
        run("UID STORE ${UidCommands.set(uids)} ${if (add) '+' else '-'}FLAGS.SILENT ($flags)")
    }

    /** Removes exactly [uids] (which must be marked `\Deleted`); nothing else is expunged. */
    override fun uidExpunge(uids: Collection<Long>) {
        check(capabilities.uidPlus) { "UID EXPUNGE needs UIDPLUS" }
        run("UID EXPUNGE ${UidCommands.set(uids)}")
    }

    /** UIDs ≥ [minUid] whose Message-ID header is [messageId]. */
    override fun searchMessageId(
        messageId: String,
        minUid: Long,
    ): List<Long> =
        // "n:*" also matches the highest UID when that is below n, so filter again.
        searchHeader("UID ${minUid.coerceAtLeast(1)}:*", messageId).filter { it >= minUid }

    /** Whether [uid] exists and carries [messageId] (and the `\Draft` flag when [draft]). */
    override fun hasMessageId(
        uid: Long,
        messageId: String,
        draft: Boolean,
    ): Boolean = uid in searchHeader("UID $uid${if (draft) " DRAFT" else ""}", messageId)

    override fun storeGmailLabels(
        uids: Collection<Long>,
        labels: List<String>,
        add: Boolean,
    ) {
        check(capabilities.gmail) { "X-GM-LABELS needs Gmail" }
        run(UidCommands.gmailLabels(uids, labels, add))
    }

    /** Closes without expunging (Angus sends UNSELECT, or EXAMINE + CLOSE; neither removes anything). */
    override fun close() {
        if (folder.isOpen) runCatching { folder.close(false) }
    }

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
        /** SELECT (read-write) or EXAMINE [path]. Close it (or `use` it); closing never expunges. */
        fun open(
            connection: ImapConnection,
            path: String,
            capabilities: Capabilities,
            readWrite: Boolean,
        ): UidFolder {
            val folder = connection.imapStore.getFolder(path) as IMAPFolder
            folder.open(if (readWrite) Folder.READ_WRITE else Folder.READ_ONLY)
            return UidFolder(folder, capabilities)
        }

        fun <T> withReadWrite(
            connection: ImapConnection,
            path: String,
            capabilities: Capabilities,
            block: (UidFolder) -> T,
        ): T = open(connection, path, capabilities, readWrite = true).use(block)

        /** EXAMINE: for searches that must not change anything. */
        fun <T> withReadOnly(
            connection: ImapConnection,
            path: String,
            capabilities: Capabilities,
            block: (UidFolder) -> T,
        ): T = open(connection, path, capabilities, readWrite = false).use(block)
    }
}

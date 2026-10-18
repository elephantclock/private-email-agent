package tech.elephantclock.privatemail.core.mail.ops

import org.eclipse.angus.mail.imap.IMAPFolder
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.FolderPaths
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.imap.Namespace
import tech.elephantclock.privatemail.core.mail.imap.RemoteFolder
import tech.elephantclock.privatemail.core.mail.imap.SelectedFolder
import tech.elephantclock.privatemail.core.mail.imap.UidFolder

/** UIDVALIDITY and UIDNEXT from STATUS (UIDNEXT ≤ 0 when the server didn't say). */
internal data class FolderStatus(
    val uidValidity: Long,
    val uidNext: Long,
)

/** The server operations the move engine needs. [ImapMailbox] on a server; fakes in tests. */
internal interface Mailbox {
    /** What the engine relies on; tests switch MOVE, UIDPLUS or Gmail on and off. */
    val capabilities: Capabilities

    fun folders(): List<RemoteFolder>

    fun namespace(): Namespace

    /** Creates and subscribes [path] unless it (or a case-variant in [folders]) exists; returns the path used. */
    fun ensureFolder(
        folders: List<RemoteFolder>,
        path: String,
    ): String

    fun status(path: String): FolderStatus

    /** SELECT (read-write) or EXAMINE; closing never expunges. */
    fun open(
        path: String,
        readWrite: Boolean,
    ): SelectedFolder
}

internal class ImapMailbox(
    private val connection: ImapConnection,
    override val capabilities: Capabilities = connection.capabilities,
) : Mailbox {
    override fun folders() = connection.listFolders()

    override fun namespace() = FolderPaths.namespace(connection)

    override fun ensureFolder(
        folders: List<RemoteFolder>,
        path: String,
    ) = FolderPaths.ensure(connection, folders, path)

    override fun status(path: String): FolderStatus {
        val folder = connection.imapStore.getFolder(path) as IMAPFolder
        return FolderStatus(folder.uidValidity, folder.uidNext)
    }

    override fun open(
        path: String,
        readWrite: Boolean,
    ): SelectedFolder = UidFolder.open(connection, path, capabilities, readWrite)
}

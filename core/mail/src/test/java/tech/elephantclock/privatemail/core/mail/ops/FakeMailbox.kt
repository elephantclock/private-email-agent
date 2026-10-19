package tech.elephantclock.privatemail.core.mail.ops

import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.CopyUid
import tech.elephantclock.privatemail.core.mail.imap.Namespace
import tech.elephantclock.privatemail.core.mail.imap.RemoteFolder
import tech.elephantclock.privatemail.core.mail.imap.SelectedFolder
import tech.elephantclock.privatemail.core.mail.imap.UidCommands
import tech.elephantclock.privatemail.core.model.SpecialUse

/**
 * An in-memory server for logic GreenMail can't exercise (Gmail labels, UIDVALIDITY changes,
 * namespaces). Records every command it receives; Gmail mode follows Gmail's semantics: a label
 * is a folder, adding one gives the message a UID there, removing `\Inbox` removes it from INBOX.
 */
internal class FakeMailbox(
    override val capabilities: Capabilities,
    private val namespace: Namespace = Namespace("", '/'),
) : Mailbox {
    class Folder(
        var uidValidity: Long,
        var nextUid: Long = 1,
        val specialUse: SpecialUse? = null,
    ) {
        /** UID → Message-ID. */
        val messages = sortedMapOf<Long, String>()
        val deleted = mutableSetOf<Long>()

        fun add(messageId: String): Long = nextUid++.also { messages[it] = messageId }
    }

    val folders = linkedMapOf("INBOX" to Folder(1, specialUse = SpecialUse.INBOX))
    val commands = mutableListOf<String>()

    /** Set to make COPY/MOVE omit COPYUID even with UIDPLUS (GreenMail's MOVE does this). */
    var sendCopyUid = true

    fun folder(path: String) = folders.getValue(path)

    override fun folders() =
        folders.map { (path, f) ->
            RemoteFolder(path, namespace.separator, emptySet(), f.specialUse, selectable = true)
        }

    override fun namespace() = namespace

    override fun ensureFolder(
        folders: List<RemoteFolder>,
        path: String,
    ): String {
        commands += "CREATE $path"
        this.folders.getOrPut(path) { Folder(uidValidity = 100L + this.folders.size) }
        return path
    }

    override fun status(path: String) = folder(path).let { FolderStatus(it.uidValidity, it.nextUid) }

    override fun open(
        path: String,
        readWrite: Boolean,
    ): SelectedFolder {
        commands += (if (readWrite) "SELECT " else "EXAMINE ") + path
        return Selected(path, folder(path))
    }

    private inner class Selected(
        override val path: String,
        private val f: Folder,
    ) : SelectedFolder {
        override val uidValidity get() = f.uidValidity

        override fun existing(uids: Collection<Long>) = uids.filter { it in f.messages }.toSet()

        private fun copyTo(
            uids: Collection<Long>,
            destination: String,
        ): CopyUid? {
            val dest = folder(destination)
            val map = uids.sorted().associateWith { dest.add(f.messages.getValue(it)) }
            return if (capabilities.uidPlus && sendCopyUid) CopyUid(dest.uidValidity, map) else null
        }

        override fun uidMove(
            uids: Collection<Long>,
            destination: String,
        ): CopyUid? {
            check(capabilities.move)
            commands += "UID MOVE ${UidCommands.set(uids)} $destination"
            return copyTo(uids, destination).also { uids.forEach { f.messages.remove(it) } }
        }

        override fun uidCopy(
            uids: Collection<Long>,
            destination: String,
        ): CopyUid? {
            commands += "UID COPY ${UidCommands.set(uids)} $destination"
            return copyTo(uids, destination)
        }

        override fun storeFlags(
            uids: Collection<Long>,
            flags: String,
            add: Boolean,
        ) {
            commands += "UID STORE ${UidCommands.set(uids)} ${if (add) '+' else '-'}FLAGS ($flags)"
            if (flags == "\\Deleted") {
                if (add) {
                    f.deleted += uids
                } else {
                    f.deleted -= uids.toSet()
                }
            }
        }

        override fun uidExpunge(uids: Collection<Long>) {
            check(capabilities.uidPlus)
            commands += "UID EXPUNGE ${UidCommands.set(uids)}"
            uids.filter { it in f.deleted }.forEach {
                f.messages.remove(it)
                f.deleted.remove(it)
            }
        }

        override fun storeGmailLabels(
            uids: Collection<Long>,
            labels: List<String>,
            add: Boolean,
        ) {
            check(capabilities.gmail)
            commands += UidCommands.gmailLabels(uids, labels, add)
            for (label in labels) {
                when {
                    add -> {
                        // Gmail's \Inbox label is the INBOX folder.
                        val name = if (label == "\\Inbox") "INBOX" else label
                        val target = folders.getOrPut(name) { Folder(200L + folders.size) }
                        uids.forEach { uid ->
                            val id = f.messages.getValue(uid)
                            if (id !in target.messages.values) target.add(id)
                        }
                    }

                    label == "\\Inbox" && path == "INBOX" -> {
                        uids.forEach { f.messages.remove(it) }
                    }
                }
            }
        }

        override fun searchMessageId(
            messageId: String,
            minUid: Long,
        ) = f.messages
            .filter { (uid, id) -> uid >= minUid && id == messageId }
            .keys
            .toList()

        override fun hasMessageId(
            uid: Long,
            messageId: String,
            draft: Boolean,
        ) = f.messages[uid] == messageId

        override fun close() {
            commands += "UNSELECT $path"
        }
    }
}

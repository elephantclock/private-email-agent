package tech.elephantclock.privatemail.core.mail.ops

import tech.elephantclock.privatemail.core.mail.imap.FolderPaths
import tech.elephantclock.privatemail.core.mail.imap.RemoteFolder
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.SpecialUse

/**
 * Finds or creates the folder for a triage target: the value folders (People, Money, Work,
 * Orders, Reports, custom ones) and Discarded are created on demand in the personal namespace
 * ("INBOX.Work" on INBOX-prefixed servers); Junk uses the server's special-use folder. Never
 * renames or deletes anything.
 */
internal class FolderProvisioner(
    private val mailbox: Mailbox,
) {
    private var cached: List<RemoteFolder>? = null

    private fun folders(): List<RemoteFolder> = cached ?: mailbox.folders().also { cached = it }

    /** The folder path for [target]; with [create] false, null when it doesn't exist yet. */
    fun pathFor(
        target: FolderTarget,
        create: Boolean = true,
    ): String? {
        if (target.role == FolderRole.PRIORITY) return INBOX
        if (target.role == FolderRole.JUNK) {
            FolderPaths.findSpecial(folders(), SpecialUse.JUNK)?.let { return it.path }
        }
        val path = mailbox.namespace().child(leafName(target))
        FolderPaths.findExisting(folders(), path)?.let { return it.path }
        if (!create) return null
        return mailbox.ensureFolder(folders(), path).also { cached = null }
    }

    /** The special-use folder, if the server has one (we don't create Trash: a wrong guess loses mail). */
    fun special(use: SpecialUse): String? = FolderPaths.findSpecial(folders(), use)?.path

    companion object {
        const val INBOX = "INBOX"

        fun leafName(target: FolderTarget): String =
            when (target.role) {
                FolderRole.PRIORITY -> INBOX
                FolderRole.PEOPLE -> "People"
                FolderRole.MONEY -> "Money"
                FolderRole.WORK -> "Work"
                FolderRole.ORDERS -> "Orders"
                FolderRole.REPORTS -> "Reports"
                FolderRole.CUSTOM -> requireNotNull(target.customName)
                FolderRole.DISCARDED -> "Discarded"
                FolderRole.JUNK -> "Junk"
            }
    }
}

package tech.elephantclock.privatemail.core.mail.imap

import jakarta.mail.Folder
import jakarta.mail.MessagingException
import org.eclipse.angus.mail.imap.IMAPFolder
import tech.elephantclock.privatemail.core.model.SpecialUse
import java.util.Locale

/**
 * Where new folders go: the personal namespace (e.g. "INBOX" with '.' on Courier-style
 * servers, "" with '/' on Dovecot and Gmail) and the hierarchy separator.
 */
data class Namespace(
    val prefix: String,
    val separator: Char?,
) {
    /** Full path of a top-level folder [leaf] in this namespace. A separator in [leaf] becomes '-'. */
    fun child(leaf: String): String {
        val clean = leaf.trim().let { name -> separator?.let { name.replace(it, '-') } ?: name }
        require(clean.isNotEmpty()) { "folder name must not be blank" }
        return when {
            prefix.isEmpty() -> clean
            separator == null -> clean
            prefix.endsWith(separator) -> prefix + clean
            else -> "$prefix$separator$clean"
        }
    }
}

object FolderPaths {
    /** Default names for folders we may have to create. */
    private val defaultNames =
        mapOf(
            SpecialUse.SENT to "Sent",
            SpecialUse.DRAFTS to "Drafts",
            SpecialUse.TRASH to "Trash",
            SpecialUse.JUNK to "Junk",
            SpecialUse.ARCHIVE to "Archive",
        )

    /** The first personal namespace (Angus falls back to the root when NAMESPACE is missing). */
    internal fun namespace(connection: ImapConnection): Namespace {
        val store = connection.imapStore
        val personal = store.personalNamespaces.firstOrNull() ?: store.defaultFolder
        val separator = personal.separator.takeIf { it != '\u0000' }
        return Namespace(personal.fullName, separator)
    }

    /**
     * An existing folder at [path], else one whose path matches ignoring case (servers differ on
     * case sensitivity; creating "Work" next to "work" confuses every client).
     */
    fun findExisting(
        folders: List<RemoteFolder>,
        path: String,
    ): RemoteFolder? =
        folders.firstOrNull { it.path == path && it.selectable }
            ?: folders.firstOrNull { it.path.lowercase(Locale.ROOT) == path.lowercase(Locale.ROOT) && it.selectable }

    /** The selectable folder with [use], or null. */
    fun findSpecial(
        folders: List<RemoteFolder>,
        use: SpecialUse,
    ): RemoteFolder? = folders.firstOrNull { it.specialUse == use && it.selectable }

    /**
     * Path of the folder with [use]: the existing one, or a new one named after the role in the
     * personal namespace. Creates at most one folder and never deletes or renames anything.
     */
    internal fun ensureSpecial(
        connection: ImapConnection,
        use: SpecialUse,
    ): String {
        val folders = connection.listFolders()
        findSpecial(folders, use)?.let { return it.path }
        val name = requireNotNull(defaultNames[use]) { "won't create $use" }
        return ensure(connection, folders, namespace(connection).child(name))
    }

    /** [path], or an existing case-variant of it, creating and subscribing it when missing. */
    internal fun ensure(
        connection: ImapConnection,
        folders: List<RemoteFolder>,
        path: String,
    ): String {
        findExisting(folders, path)?.let { return it.path }
        val folder = connection.imapStore.getFolder(path) as IMAPFolder
        if (!folder.exists()) {
            // Another client may create it at the same moment; only a folder that still doesn't exist is an error.
            val created = runCatching { folder.create(Folder.HOLDS_MESSAGES) }.getOrDefault(false)
            if (!created && !folder.exists()) throw MessagingException("could not create folder")
        }
        runCatching { folder.isSubscribed = true }
        return path
    }
}

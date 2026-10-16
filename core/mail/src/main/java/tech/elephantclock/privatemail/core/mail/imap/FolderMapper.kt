package tech.elephantclock.privatemail.core.mail.imap

import tech.elephantclock.privatemail.core.model.SpecialUse
import java.util.Locale

/** A folder as LISTed by the server. */
data class RemoteFolder(
    val path: String,
    val separator: Char?,
    val attributes: Set<String>,
    val specialUse: SpecialUse?,
    /** False for `\Noselect` containers such as "[Gmail]". */
    val selectable: Boolean,
)

/** Finds Sent, Drafts, Trash, Junk, Archive and All Mail from RFC 6154 attributes, else by name. */
object FolderMapper {
    private val byAttribute =
        mapOf(
            "\\SENT" to SpecialUse.SENT,
            "\\DRAFTS" to SpecialUse.DRAFTS,
            "\\TRASH" to SpecialUse.TRASH,
            "\\JUNK" to SpecialUse.JUNK,
            "\\ARCHIVE" to SpecialUse.ARCHIVE,
            "\\ALL" to SpecialUse.ALL,
        )

    /** Common names, matched case-insensitively against the last path segment. */
    private val byName: Map<String, SpecialUse> =
        buildMap {
            listOf("sent", "sent items", "sent messages", "sent mail", "отправленные", "gesendet", "envoyés")
                .forEach { put(it, SpecialUse.SENT) }
            listOf("drafts", "draft", "черновики", "entwürfe", "brouillons").forEach { put(it, SpecialUse.DRAFTS) }
            listOf("trash", "deleted items", "deleted messages", "bin", "корзина", "papierkorb", "corbeille")
                .forEach { put(it, SpecialUse.TRASH) }
            listOf(
                "junk",
                "spam",
                "junk e-mail",
                "junk email",
                "bulk mail",
                "спам",
            ).forEach { put(it, SpecialUse.JUNK) }
            listOf("archive", "archives", "архив").forEach { put(it, SpecialUse.ARCHIVE) }
            put("all mail", SpecialUse.ALL)
        }

    fun specialUseOf(
        path: String,
        separator: Char?,
        attributes: Collection<String>,
    ): SpecialUse? {
        if (path.equals("INBOX", ignoreCase = true)) return SpecialUse.INBOX
        attributes.firstNotNullOfOrNull { byAttribute[it.uppercase(Locale.ROOT)] }?.let { return it }
        val leaf = if (separator != null) path.substringAfterLast(separator) else path
        return byName[leaf.lowercase(Locale.ROOT)]
    }

    /**
     * Maps a whole LIST. Where two folders claim the same role, the one with the server's
     * attribute wins over a name match, so "[Gmail]/Sent Mail" beats a stray "Sent".
     */
    fun mapAll(raw: List<Triple<String, Char?, Set<String>>>): List<RemoteFolder> {
        val folders =
            raw.map { (path, sep, attrs) ->
                val upper = attrs.map { it.uppercase(Locale.ROOT) }.toSet()
                RemoteFolder(path, sep, attrs, specialUseOf(path, sep, attrs), "\\NOSELECT" !in upper)
            }
        val claimedByAttribute =
            folders
                .filter { f -> f.attributes.any { byAttribute.containsKey(it.uppercase(Locale.ROOT)) } }
                .mapNotNull { it.specialUse }
                .toSet()
        return folders.map { f ->
            val fromName = f.attributes.none { byAttribute.containsKey(it.uppercase(Locale.ROOT)) }
            if (fromName && f.specialUse in claimedByAttribute && f.specialUse != SpecialUse.INBOX) {
                f.copy(specialUse = null)
            } else {
                f
            }
        }
    }
}

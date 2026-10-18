package tech.elephantclock.privatemail.core.mail.imap

import org.eclipse.angus.mail.imap.protocol.BASE64MailboxEncoder

/** `[COPYUID ...]` from a COPY or MOVE reply: which new UID each source UID got (RFC 4315). */
data class CopyUid(
    val uidValidity: Long,
    val newUids: Map<Long, Long>,
)

/**
 * Builds the UID sets we send and parses the replies we read back. Pure, so it is unit-tested.
 * We never send sequence numbers and never `*`: every command names exact UIDs.
 */
object UidCommands {
    /** Most UIDs per command, so command lines stay short and a failure affects one batch. */
    const val BATCH_SIZE = 100

    /** Above this many UIDs in one reply set we stop expanding (a broken or hostile server). */
    private const val MAX_EXPANDED = 10_000

    private val copyUidPattern = Regex("""\[COPYUID (\d+) ([0-9:,]+) ([0-9:,]+)]""", RegexOption.IGNORE_CASE)
    private val searchPattern = Regex("""^\* SEARCH\b(.*)$""", RegexOption.IGNORE_CASE)
    private val messageIdPattern = Regex("""<[^<>\s]+@[^<>\s]+>""")

    /** "1:3,7,9:10" from any positive UIDs, sorted and deduplicated. */
    fun set(uids: Collection<Long>): String {
        require(uids.isNotEmpty()) { "empty UID set" }
        require(uids.all { it > 0 }) { "UIDs must be positive" }
        val sorted = uids.toSortedSet().toList()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var previous = start
        for (uid in sorted.drop(1)) {
            if (uid != previous + 1) {
                parts += range(start, previous)
                start = uid
            }
            previous = uid
        }
        parts += range(start, previous)
        return parts.joinToString(",")
    }

    /** Sorted, deduplicated batches of at most [size]. */
    fun batches(
        uids: Collection<Long>,
        size: Int = BATCH_SIZE,
    ): List<List<Long>> = uids.toSortedSet().toList().chunked(size)

    /** Expands a reply set like "4:6,9" in the order written; null when malformed or huge. */
    fun parseSet(text: String): List<Long>? {
        val out = mutableListOf<Long>()
        for (part in text.split(',')) {
            val bounds = part.split(':').map { it.toLongOrNull() ?: return null }
            when (bounds.size) {
                1 -> {
                    out += bounds[0]
                }

                2 -> {
                    val (a, b) = bounds
                    if (kotlin.math.abs(b - a) + out.size >= MAX_EXPANDED) return null
                    out += if (a <= b) (a..b).toList() else (a downTo b).toList()
                }

                else -> {
                    return null
                }
            }
            if (out.size > MAX_EXPANDED) return null
        }
        return out.takeIf { list -> list.all { it > 0 } }
    }

    /** The first well-formed COPYUID in any reply line, or null (no UIDPLUS, or a mismatch). */
    fun parseCopyUid(lines: List<String>): CopyUid? {
        for (line in lines) {
            val match = copyUidPattern.find(line) ?: continue
            val validity = match.groupValues[1].toLongOrNull() ?: continue
            val source = parseSet(match.groupValues[2]) ?: continue
            val target = parseSet(match.groupValues[3]) ?: continue
            if (source.size != target.size || validity <= 0) continue
            return CopyUid(validity, source.zip(target).toMap())
        }
        return null
    }

    /** UIDs from `* SEARCH` lines. */
    fun parseSearch(lines: List<String>): List<Long> =
        lines.flatMap { line ->
            val rest = searchPattern.find(line.trim())?.groupValues?.get(1) ?: return@flatMap emptyList()
            rest.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        }

    /**
     * `UID STORE <uids> +X-GM-LABELS (<labels>)` (or `-`). System labels such as `\\Inbox` go
     * as atoms; others as quoted strings in modified UTF-7, like mailbox names.
     */
    fun gmailLabels(
        uids: Collection<Long>,
        labels: List<String>,
        add: Boolean,
    ): String {
        require(labels.isNotEmpty()) { "no labels" }
        val list = labels.joinToString(" ") { gmailLabel(it) }
        return "UID STORE ${set(uids)} ${if (add) '+' else '-'}X-GM-LABELS ($list)"
    }

    private val systemLabel = Regex("""\\[A-Za-z]+""")

    private fun gmailLabel(label: String): String {
        require(label.isNotBlank() && label.none { it == '\r' || it == '\n' }) { "bad label" }
        if (systemLabel.matches(label)) return label
        val encoded = BASE64MailboxEncoder.encode(label)
        return "\"" + encoded.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** The first `<id@host>` in a header value, or null. */
    fun messageId(value: String?): String? = value?.let { messageIdPattern.find(it)?.value }

    /** Every `<id@host>` in header values, in order. */
    fun messageIds(values: List<String>): List<String> =
        values.flatMap { v -> messageIdPattern.findAll(v).map { it.value }.toList() }

    private fun range(
        from: Long,
        to: Long,
    ) = if (from == to) "$from" else "$from:$to"
}

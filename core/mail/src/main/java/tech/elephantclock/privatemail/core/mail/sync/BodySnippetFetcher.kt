package tech.elephantclock.privatemail.core.mail.sync

import org.eclipse.angus.mail.iap.Response
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.BODY
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE
import org.eclipse.angus.mail.imap.protocol.FetchResponse
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import org.eclipse.angus.mail.imap.protocol.Item
import org.eclipse.angus.mail.imap.protocol.MessageSet

/** A message's structure and the start of its text, keyed by sequence number. */
data class Snippet(
    val structure: MimePart,
    val text: String,
    val truncated: Boolean,
    val sourceMimeType: String,
)

/**
 * Fetches BODYSTRUCTURE for a batch, picks each message's text part and fetches its first
 * [SNIPPET_BYTES] with `BODY.PEEK[section]<0.N>`. Requests are grouped by section, so a batch
 * costs a handful of round trips, not one per message. PEEK never sets \Seen.
 */
object BodySnippetFetcher {
    const val SNIPPET_BYTES = 4096

    fun fetch(
        folder: IMAPFolder,
        sequenceNumbers: List<Int>,
    ): Map<Int, Snippet> {
        if (sequenceNumbers.isEmpty()) return emptyMap()
        val result =
            folder.doCommand { p ->
                val protocol = p as IMAPProtocol
                val structures =
                    items(protocol.fetch(sets(sequenceNumbers), "BODYSTRUCTURE"), BODYSTRUCTURE::class.java)
                        // A structure we can't make sense of means no preview for that message, not a failed batch.
                        .mapNotNull { (n, bs) -> runCatching { MimeTree.from(bs) }.getOrNull()?.let { n to it } }
                        .toMap()
                val textParts = structures.mapNotNull { (n, root) -> MimeTree.textPart(root)?.let { n to it } }.toMap()
                val bodies = mutableMapOf<Int, ByteArray>()
                textParts.entries.groupBy({ it.value.section }, { it.key }).forEach { (section, numbers) ->
                    items(
                        protocol.fetch(sets(numbers), "BODY.PEEK[$section]<0.$SNIPPET_BYTES>"),
                        BODY::class.java,
                    ).forEach { (n, body) ->
                        bodies[n] =
                            body.byteArray?.let { it.bytes.copyOfRange(it.start, it.start + it.count) } ?: ByteArray(0)
                    }
                }
                structures.mapValues { (n, root) ->
                    val part = textParts[n]
                    val raw = bodies[n]
                    if (part == null || raw == null) {
                        Snippet(root, "", truncated = false, sourceMimeType = "text/plain")
                    } else {
                        val complete = raw.size < SNIPPET_BYTES
                        // Decoding is pure; a hostile body yields an empty preview and the batch goes on.
                        val text =
                            try {
                                SnippetDecoder.decode(
                                    raw,
                                    part.encoding,
                                    part.params["charset"],
                                    part.subtype.equals("html", true),
                                    complete,
                                )
                            } catch (_: RuntimeException) {
                                ""
                            }
                        Snippet(structure = root, text = text, truncated = !complete, sourceMimeType = part.mimeType)
                    }
                }
            }
        @Suppress("UNCHECKED_CAST")
        return result as Map<Int, Snippet>
    }

    private fun sets(numbers: List<Int>) = MessageSet.createMessageSets(numbers.toIntArray())

    private fun <T : Item> items(
        responses: Array<Response>,
        type: Class<T>,
    ): Map<Int, T> =
        responses
            .filterIsInstance<FetchResponse>()
            .mapNotNull { r -> r.getItem(type)?.let { r.number to it } }
            .toMap()
}

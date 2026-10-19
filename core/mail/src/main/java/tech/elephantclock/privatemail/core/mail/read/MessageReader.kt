package tech.elephantclock.privatemail.core.mail.read

import jakarta.mail.internet.MimeUtility
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.sync.MimePart
import tech.elephantclock.privatemail.core.mail.sync.MimeTree
import tech.elephantclock.privatemail.core.mail.sync.SnippetDecoder
import java.io.File
import java.io.OutputStream

/** A message's readable text. HTML is converted to plain text (no markup, links or images are kept). */
data class FullText(
    val text: String,
    val fromHtml: Boolean,
    /** Only the first [MessageReader.MAX_TEXT_BYTES] were fetched. */
    val truncated: Boolean,
)

sealed interface ReadResult<out T> {
    data class Found<T>(
        val value: T,
    ) : ReadResult<T>

    /**
     * The message isn't where the database says: its folder's UIDVALIDITY changed, or the UID no
     * longer exists (moved or deleted elsewhere). Nothing was read, so nothing of another message
     * can be shown in its place.
     */
    data object Gone : ReadResult<Nothing>

    /** No such MIME part (the stored part id doesn't match the server's structure). */
    data object NoSuchPart : ReadResult<Nothing>

    /** The part is bigger than the limit; nothing was downloaded. */
    data class TooLarge(
        val sizeBytes: Long,
    ) : ReadResult<Nothing>
}

/**
 * Reads one message on demand for the detail screen (T45), strictly read-only: the folder is
 * opened with EXAMINE and every body fetch is `BODY.PEEK[...]`, so `\Seen` and every other flag
 * stay as they are. Blocking; run it through `ImapConnection.cancellable`.
 */
object MessageReader {
    /** Text beyond this is cut (with [FullText.truncated]); a reader rarely needs more. */
    const val MAX_TEXT_BYTES = 1_048_576

    /** Encoded attachment size limit (≈ 25 MB decoded). */
    const val MAX_ATTACHMENT_BYTES = 34_000_000L

    private const val CHUNK_BYTES = 262_144
    private val partIdPattern = Regex("^[1-9][0-9]{0,3}(\\.[1-9][0-9]{0,3}){0,15}$")

    fun body(
        connection: ImapConnection,
        folderPath: String,
        uidValidity: Long,
        uid: Long,
        maxBytes: Int = MAX_TEXT_BYTES,
    ): ReadResult<FullText> =
        withMessage(connection, folderPath, uidValidity, uid) { folder, seq ->
            command(folder) { p ->
                val root = MimeTree.from(p.fetchBodyStructure(seq))
                val part = MimeTree.textPart(root) ?: return@command ReadResult.Found(FullText("", false, false))
                val body = p.peekBody(seq, part.section, 0, maxBytes)
                val raw = body?.byteArray?.let { it.bytes.copyOfRange(it.start, it.start + it.count) } ?: ByteArray(0)
                val complete = raw.size < maxBytes
                val html = part.subtype.equals("html", ignoreCase = true)
                val text =
                    try {
                        SnippetDecoder.decode(raw, part.encoding, part.params["charset"], html, complete)
                    } catch (_: RuntimeException) {
                        ""
                    }
                ReadResult.Found(FullText(text, fromHtml = html, truncated = !complete))
            }
        }

    /**
     * Downloads attachment [partId] and writes its decoded bytes to [target] (replaced). The
     * encoded bytes are staged in a sibling temporary file, which is always removed.
     * @return the decoded size.
     */
    fun attachment(
        connection: ImapConnection,
        folderPath: String,
        uidValidity: Long,
        uid: Long,
        partId: String,
        target: File,
        maxBytes: Long = MAX_ATTACHMENT_BYTES,
    ): ReadResult<Long> {
        if (!partIdPattern.matches(partId)) return ReadResult.NoSuchPart
        return withMessage(connection, folderPath, uidValidity, uid) { folder, seq ->
            val part =
                command(folder) { p -> find(MimeTree.from(p.fetchBodyStructure(seq)), partId) }
                    ?: return@withMessage ReadResult.NoSuchPart
            if (part.isMultipart) return@withMessage ReadResult.NoSuchPart
            if (part.size > maxBytes) return@withMessage ReadResult.TooLarge(part.size)
            val staged = File.createTempFile("part", ".raw", target.parentFile)
            try {
                val fetched = staged.outputStream().use { out -> download(folder, seq, partId, out, maxBytes) }
                if (fetched > maxBytes) return@withMessage ReadResult.TooLarge(fetched)
                val decoded =
                    staged.inputStream().use { raw ->
                        val stream =
                            when (part.encoding?.lowercase()) {
                                "base64", "quoted-printable" -> MimeUtility.decode(raw, part.encoding!!.lowercase())
                                else -> raw
                            }
                        target.outputStream().use { stream.copyTo(it) }
                    }
                ReadResult.Found(decoded)
            } finally {
                staged.delete()
            }
        }
    }

    /** Fetches the part in chunks; stops (returning more than [maxBytes]) if it keeps growing. */
    private fun download(
        folder: IMAPFolder,
        seq: Int,
        partId: String,
        out: OutputStream,
        maxBytes: Long,
    ): Long {
        var offset = 0L
        while (offset <= maxBytes) {
            val chunk =
                command(folder) { p ->
                    p.peekBody(seq, partId, offset.toInt(), CHUNK_BYTES)?.byteArray?.let {
                        it.bytes.copyOfRange(it.start, it.start + it.count)
                    } ?: ByteArray(0)
                }
            out.write(chunk)
            offset += chunk.size
            if (chunk.size < CHUNK_BYTES) return offset
        }
        return offset
    }

    private fun find(
        part: MimePart,
        section: String,
    ): MimePart? = if (part.section == section) part else part.children.firstNotNullOfOrNull { find(it, section) }

    private fun <T> withMessage(
        connection: ImapConnection,
        folderPath: String,
        uidValidity: Long,
        uid: Long,
        block: (IMAPFolder, Int) -> ReadResult<T>,
    ): ReadResult<T> {
        val folder = connection.openReadOnly(folderPath)
        try {
            if (folder.uidValidity != uidValidity) return ReadResult.Gone
            val message = folder.getMessageByUID(uid) ?: return ReadResult.Gone
            return block(folder, message.messageNumber)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun <T> command(
        folder: IMAPFolder,
        block: (IMAPProtocol) -> T,
    ): T {
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { p -> block(p as IMAPProtocol) } as T
    }
}

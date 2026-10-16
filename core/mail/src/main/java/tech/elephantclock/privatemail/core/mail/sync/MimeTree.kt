package tech.elephantclock.privatemail.core.mail.sync

import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE
import tech.elephantclock.privatemail.core.model.Attachment
import java.util.Locale

/** A MIME part from BODYSTRUCTURE, with its IMAP section number ("TEXT" for a single-part message). */
data class MimePart(
    val section: String,
    val type: String,
    val subtype: String,
    val params: Map<String, String> = emptyMap(),
    val encoding: String? = null,
    val disposition: String? = null,
    val dispositionParams: Map<String, String> = emptyMap(),
    val size: Long = 0,
    val contentId: String? = null,
    val children: List<MimePart> = emptyList(),
) {
    val mimeType: String get() = "$type/$subtype".lowercase(Locale.ROOT)
    val isMultipart: Boolean get() = type.equals("multipart", ignoreCase = true)
    val fileName: String? get() = dispositionParams["filename"] ?: params["name"]
    val isAttachment: Boolean get() =
        disposition.equals("attachment", ignoreCase = true) ||
            (fileName != null && !isMultipart)
}

object MimeTree {
    fun from(bs: BODYSTRUCTURE): MimePart = convert(bs, prefix = null, isRoot = true)

    private fun convert(
        bs: BODYSTRUCTURE,
        prefix: String?,
        isRoot: Boolean,
    ): MimePart {
        val section = prefix ?: "TEXT"
        val children =
            if (bs.isMulti) {
                bs.bodies.orEmpty().mapIndexed { i, child ->
                    convert(child, if (prefix == null) "${i + 1}" else "$prefix.${i + 1}", isRoot = false)
                }
            } else {
                emptyList()
            }
        // A single-part message's body is BODY[TEXT]; a leaf inside a multipart is BODY[n].
        return MimePart(
            section = if (isRoot && !bs.isMulti) "TEXT" else section,
            type = bs.type ?: "text",
            subtype = bs.subtype ?: "plain",
            params = bs.cParams.toMap(),
            encoding = bs.encoding,
            disposition = bs.disposition,
            dispositionParams = bs.dParams.toMap(),
            size = bs.size.toLong().coerceAtLeast(0),
            contentId = bs.id,
            children = children,
        )
    }

    private fun jakarta.mail.internet.ParameterList?.toMap(): Map<String, String> {
        val list = this ?: return emptyMap()
        return list.names.toList().associate { (it as String).lowercase(Locale.ROOT) to list.get(it) }
    }

    /** The part to preview: first inline text/plain, else first inline text/html. Skips attachments and nested messages. */
    fun textPart(root: MimePart): MimePart? {
        val leaves = leaves(root).filter { !it.isAttachment && it.type.equals("text", ignoreCase = true) }
        return leaves.firstOrNull { it.subtype.equals("plain", ignoreCase = true) }
            ?: leaves.firstOrNull { it.subtype.equals("html", ignoreCase = true) }
    }

    fun attachments(root: MimePart): List<Attachment> =
        leaves(
            root,
        ).filter { it.isAttachment || (!it.type.equals("text", ignoreCase = true) && it.section != "TEXT") }.map {
            Attachment(
                partId = it.section,
                fileName = it.fileName,
                mimeType = it.mimeType,
                sizeBytes = it.size,
                isInline = it.disposition.equals("inline", ignoreCase = true),
                contentId = it.contentId?.trim('<', '>'),
            )
        }

    private fun leaves(part: MimePart): List<MimePart> =
        when {
            part.isMultipart -> part.children.flatMap(::leaves)

            part.mimeType == "message/rfc822" -> listOf(part)

            // a forwarded message: an attachment, not our text
            else -> listOf(part)
        }
}

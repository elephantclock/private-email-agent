package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable

/** The start of the text part (first ~4 KB) and the attachment list. Full bodies load on tap. */
@Serializable
data class MessageBody(
    val ref: MessageRef,
    val textPreview: String,
    /** True when [textPreview] was cut short of the full text. */
    val isTruncated: Boolean,
    /** MIME type of the part [textPreview] came from: text/plain or text/html (converted to text). */
    val sourceMimeType: String = "text/plain",
    val attachments: List<Attachment> = emptyList(),
)

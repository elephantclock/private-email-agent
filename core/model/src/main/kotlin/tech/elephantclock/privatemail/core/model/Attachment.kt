package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable

/** One attachment from BODYSTRUCTURE; its bytes are fetched only when opened. */
@Serializable
data class Attachment(
    /** IMAP section number, e.g. "2" or "1.3". */
    val partId: String,
    val fileName: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val isInline: Boolean = false,
    val contentId: String? = null,
) {
    init {
        require(partId.isNotBlank()) { "partId must not be blank" }
        require(sizeBytes >= 0) { "sizeBytes must not be negative" }
    }
}

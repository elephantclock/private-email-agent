package tech.elephantclock.privatemail.core.mail.smtp

import tech.elephantclock.privatemail.core.mail.imap.UidCommands
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageHeader
import java.security.MessageDigest
import java.util.UUID

/** The message being replied to: what threading needs from it. */
data class ReplyParent(
    val messageId: String,
    val references: List<String> = emptyList(),
    val inReplyTo: String? = null,
) {
    init {
        require(UidCommands.messageId(messageId) == messageId) { "not a Message-ID: $messageId" }
    }

    companion object {
        /** Null when the parent has no usable Message-ID (then the reply can't be threaded). */
        fun of(header: MessageHeader): ReplyParent? =
            UidCommands.messageId(header.messageId)?.let { id ->
                ReplyParent(id, UidCommands.messageIds(header.references), UidCommands.messageId(header.inReplyTo))
            }
    }
}

/**
 * A plain-text message to send or keep as a draft. [messageId] stays the same across draft
 * saves and the final send, so the Sent copy, the delivered copy and the draft all match.
 */
data class OutgoingMessage(
    val from: Address,
    val subject: String,
    val text: String,
    val to: List<Address> = emptyList(),
    val cc: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val parent: ReplyParent? = null,
    val messageId: String = newMessageId(from.domain),
) {
    init {
        require(UidCommands.messageId(messageId) == messageId) { "not a Message-ID: $messageId" }
        require('\r' !in subject && '\n' !in subject) { "subject must be one line" }
        require((to + cc + bcc + from).all { a -> a.email.none { it.isWhitespace() || it.isISOControl() } }) {
            "addresses must not contain whitespace or control characters"
        }
    }

    /** Envelope recipients, each once. Bcc is among them but never in the delivered headers. */
    val recipients: List<Address> get() = (to + cc + bcc).distinctBy { it.email }

    /** `In-Reply-To`: the parent's Message-ID. */
    val inReplyTo: String? get() = parent?.messageId

    /**
     * `References` (RFC 5322 § 3.6.4): the parent's References (or, without them, its single
     * In-Reply-To), then the parent's Message-ID. Long chains keep the first id and the most
     * recent ones.
     */
    val references: List<String>
        get() {
            val p = parent ?: return emptyList()
            val base = p.references.ifEmpty { listOfNotNull(p.inReplyTo) }
            val all = (base + p.messageId).distinct()
            return if (all.size <= MAX_REFERENCES) all else listOf(all.first()) + all.takeLast(MAX_REFERENCES - 1)
        }

    /** SHA-256 over every field that ends up in the message; see [SendApproval]. */
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun field(value: String?) {
            val bytes = (value ?: "\u0000null").toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray())
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }

        fun addresses(list: List<Address>) {
            field(list.size.toString())
            list.forEach {
                field(it.email)
                field(it.name)
            }
        }
        field(from.email)
        field(from.name)
        addresses(to)
        addresses(cc)
        addresses(bcc)
        field(subject)
        field(text)
        field(messageId)
        field(inReplyTo)
        field(references.joinToString(" "))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_REFERENCES = 20

        /** `<random@sender-domain>`: never the device's host name. */
        fun newMessageId(domain: String): String = "<${UUID.randomUUID()}@$domain>"
    }
}

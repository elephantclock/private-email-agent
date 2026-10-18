package tech.elephantclock.privatemail.core.triage.golden

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import tech.elephantclock.privatemail.core.triage.MailItem
import java.io.InputStream
import java.util.Properties

/**
 * Turns a fixture `.eml` into the [MailItem] the app would build from the same message: only
 * the headers `HeaderFetcher` fetches, every `Authentication-Results` in header order, the raw
 * `In-Reply-To`, and a text preview of about 4 KB (text/plain preferred over HTML).
 */
object EmlParser {
    /** Copy of `core:mail`'s `HeaderFetcher.TRIAGE_HEADERS` (this module can't depend on it); keep in step. */
    val TRIAGE_HEADERS =
        listOf(
            "List-Unsubscribe",
            "List-Unsubscribe-Post",
            "List-Id",
            "Precedence",
            "Auto-Submitted",
            "Authentication-Results",
            "References",
            "In-Reply-To",
            "Message-ID",
            "Return-Path",
            "X-Mailgun-Sid",
            "X-SG-EID",
            "X-MC-User",
            "X-Campaign",
            "Feedback-ID",
        )

    private const val PREVIEW_CHARS = 4096
    private val session = Session.getInstance(Properties())

    fun parse(
        input: InputStream,
        account: AccountId,
        uid: Long,
    ): MailItem {
        val m = MimeMessage(session, input)
        val ref = MessageRef(account, "INBOX", uidValidity = 1, uid = uid)
        val received = m.sentDate?.time ?: 0L
        val header =
            MessageHeader(
                ref = ref,
                messageId = m.messageID,
                from = (m.from?.firstOrNull() as? InternetAddress)?.toAddress(),
                subject = m.subject.orEmpty(),
                sentAtMillis = m.sentDate?.time,
                receivedAtMillis = received,
                to = m.getRecipients(jakarta.mail.Message.RecipientType.TO).toAddresses(),
                cc = m.getRecipients(jakarta.mail.Message.RecipientType.CC).toAddresses(),
                inReplyTo = m.getHeader("In-Reply-To")?.firstOrNull()?.trim(),
                references =
                    m
                        .getHeader("References")
                        ?.flatMap { it.split(' ', '\t', '\r', '\n') }
                        ?.filter { it.isNotBlank() }
                        .orEmpty(),
                headers =
                    TRIAGE_HEADERS
                        .mapNotNull { name -> m.getHeader(name)?.let { name.lowercase() to it.toList() } }
                        .toMap(),
            )
        val (text, mime) = preview(m) ?: ("" to "text/plain")
        val body = MessageBody(ref, text.take(PREVIEW_CHARS), text.length > PREVIEW_CHARS, mime)
        return MailItem(header, body)
    }

    private fun InternetAddress.toAddress(): Address? = address?.takeIf { '@' in it }?.let { Address.of(it, personal) }

    private fun Array<jakarta.mail.Address>?.toAddresses(): List<Address> =
        this.orEmpty().filterIsInstance<InternetAddress>().mapNotNull { it.toAddress() }

    /** The first text/plain part, else the first text/html part as rough text. */
    private fun preview(part: Part): Pair<String, String>? {
        val parts = leaves(part)
        parts.firstOrNull { it.isMimeType("text/plain") }?.let { return (it.content as String) to "text/plain" }
        parts.firstOrNull { it.isMimeType("text/html") }?.let { return htmlToText(it.content as String) to "text/html" }
        return null
    }

    private fun leaves(part: Part): List<Part> =
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as Multipart
            (0 until mp.count).flatMap { leaves(mp.getBodyPart(it)) }
        } else {
            listOf(part)
        }

    private fun htmlToText(html: String): String =
        html
            .replace(Regex("(?is)<(style|script)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

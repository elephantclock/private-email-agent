package tech.elephantclock.privatemail.core.mail.smtp

import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import tech.elephantclock.privatemail.core.model.Address
import java.util.Date
import java.util.Properties

/** Builds the MIME message for an [OutgoingMessage]. */
internal object MimeBuilder {
    /** A session with no transport settings; only used to hold the message. */
    private val plainSession: Session = Session.getInstance(Properties())

    /** Keeps our Message-ID: `saveChanges()` (called by every send) would otherwise invent one from the host name. */
    private class FixedIdMessage(
        session: Session,
        private val id: String,
    ) : MimeMessage(session) {
        override fun updateMessageID() = setHeader("Message-ID", id)
    }

    fun build(
        message: OutgoingMessage,
        nowMillis: Long,
        flags: Flags = Flags(),
        strict: Boolean,
    ): MimeMessage =
        FixedIdMessage(plainSession, message.messageId).apply {
            fun List<Address>.toInternet() = map { it.toInternet(strict) }.toTypedArray()
            setFrom(message.from.toInternet(strict))
            if (message.to.isNotEmpty()) setRecipients(Message.RecipientType.TO, message.to.toInternet())
            if (message.cc.isNotEmpty()) setRecipients(Message.RecipientType.CC, message.cc.toInternet())
            // Kept here for the Sent copy and drafts; SMTPTransport never writes Bcc into what it delivers.
            if (message.bcc.isNotEmpty()) setRecipients(Message.RecipientType.BCC, message.bcc.toInternet())
            setSubject(message.subject, "UTF-8")
            setSentDate(Date(nowMillis))
            message.inReplyTo?.let { setHeader("In-Reply-To", it) }
            message.references.takeIf { it.isNotEmpty() }?.let { refs ->
                setHeader("References", MimeUtility.fold("References: ".length, refs.joinToString(" ")))
            }
            setText(message.text, "UTF-8", "plain")
            setFlags(flags, true)
            saveChanges()
        }

    /**
     * With [strict] (sending), a malformed address throws `AddressException`. Drafts may hold
     * unfinished ones ("bob@"); header injection is ruled out by [OutgoingMessage] either way.
     */
    private fun Address.toInternet(strict: Boolean): InternetAddress =
        InternetAddress(email, name, "UTF-8").apply {
            if (strict) InternetAddress(email, true).validate()
        }
}

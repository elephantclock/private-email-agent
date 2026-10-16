package tech.elephantclock.privatemail.core.data.db

import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.Attachment
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import tech.elephantclock.privatemail.core.model.ServerConfig
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource

object DbFixtures {
    val accountId = AccountId("acc-1")

    val account =
        Account(
            id = accountId,
            email = Address.of("me@example.com", "Me"),
            displayName = "Work",
            username = "me@example.com",
            imap = ServerConfig("imap.example.com", 993, ConnectionSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.example.com", 587, ConnectionSecurity.STARTTLS),
        )

    fun header(
        uid: Long,
        subject: String = "Subject $uid",
        from: String = "sender$uid@shop.example",
        fromName: String? = "Sender $uid",
        receivedAt: Long = 1_760_000_000_000 + uid * 1_000,
        folder: String = "INBOX",
        account: AccountId = accountId,
    ) = MessageHeader(
        ref = MessageRef(account, folder, 7, uid),
        messageId = "<m$uid@shop.example>",
        from = Address.of(from, fromName),
        subject = subject,
        sentAtMillis = receivedAt - 500,
        receivedAtMillis = receivedAt,
        to = listOf(Address.of("me@example.com")),
        references = listOf("<root@shop.example>"),
        flags = setOf(MessageFlag.SEEN, MessageFlag.FLAGGED),
        sizeBytes = 1234,
        headers = mapOf("authentication-results" to listOf("mx; dmarc=fail", "forged; dmarc=pass")),
    )

    fun body(
        header: MessageHeader,
        text: String = "Preview of ${header.subject}",
    ) = MessageBody(
        ref = header.ref,
        textPreview = text,
        isTruncated = false,
        attachments = listOf(Attachment("2", "invoice.pdf", "application/pdf", 3000)),
    )

    fun entity(
        uid: Long,
        subject: String = "Subject $uid",
        text: String? = null,
        receivedAt: Long = 1_760_000_000_000 + uid * 1_000,
        from: String = "sender$uid@shop.example",
        fromName: String? = "Sender $uid",
    ): MessageEntity {
        val h = header(uid, subject, from = from, fromName = fromName, receivedAt = receivedAt)
        return messageEntity(h, body(h, text ?: "Preview of $subject"))
    }

    val keep = Verdict.keep(Importance.HIGH, VerdictSource.RULE, "Replied before")
    val discard = Verdict.discard(VerdictSource.RULE, "Newsletter")
}

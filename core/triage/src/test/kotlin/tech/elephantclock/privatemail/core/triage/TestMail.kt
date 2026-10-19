package tech.elephantclock.privatemail.core.triage

import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef

/** Builds synthetic messages for tests. */
object TestMail {
    val account = AccountId("acc-1")
    val me = Address.of("me@izi.travel", "Me")
    private var uid = 1L

    fun mail(
        from: String? = "someone@example.org",
        name: String? = null,
        subject: String = "Hello",
        preview: String? = null,
        headers: Map<String, List<String>> = emptyMap(),
        inReplyTo: String? = null,
        references: List<String> = emptyList(),
        messageId: String? = "<m$uid@example.org>",
        to: List<Address> = listOf(me),
        replyTo: List<Address> = emptyList(),
    ): MailItem {
        val ref = MessageRef(account, "INBOX", uidValidity = 1, uid = uid++)
        val header =
            MessageHeader(
                ref = ref,
                messageId = messageId,
                from = from?.let { Address.of(it, name) },
                subject = subject,
                sentAtMillis = 1_760_000_000_000,
                receivedAtMillis = 1_760_000_000_000,
                to = to,
                replyTo = replyTo,
                inReplyTo = inReplyTo,
                references = references,
                headers = headers.mapKeys { it.key.lowercase() },
            )
        return MailItem(header, preview?.let { MessageBody(ref, it, isTruncated = false) })
    }

    fun h(vararg pairs: Pair<String, String>): Map<String, List<String>> =
        pairs.groupBy({ it.first.lowercase() }, { it.second })
}

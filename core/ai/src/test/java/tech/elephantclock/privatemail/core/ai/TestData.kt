package tech.elephantclock.privatemail.core.ai

import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef

object TestData {
    val folders =
        listOf(
            FolderOption("priority", FolderTarget.Priority, "Priority", "Needs me: people, deadlines"),
            FolderOption("money", FolderTarget(FolderRole.MONEY), "Money", "Invoices, payments, receipts"),
            FolderOption("orders", FolderTarget(FolderRole.ORDERS), "Orders & travel", "Bookings and deliveries"),
            FolderOption("custom-1", FolderTarget.custom("Kids school"), "Kids school", "Mail from the school"),
            FolderOption("discarded", FolderTarget.Discarded, "Discarded", "Marketing and newsletters"),
            FolderOption("junk", FolderTarget.Junk, "Junk", "Spam and phishing"),
        )

    fun item(
        key: String,
        receivedAt: Long = 1_000,
        subject: String = "Subject $key",
        preview: String = "Hello",
        from: String = "someone@example.com",
    ) = ClassifyItem(key, Address.of(from), subject, preview, receivedAt)

    fun header(
        uid: Long = 7,
        messageId: String? = "<m$uid@example.com>",
        subject: String = "Lunch?",
        receivedAt: Long = 5_000,
    ) = MessageHeader(
        ref = MessageRef(AccountId("acc"), "INBOX", 3, uid),
        messageId = messageId,
        from = Address.of("ann@example.com", "Ann"),
        subject = subject,
        sentAtMillis = receivedAt - 10,
        receivedAtMillis = receivedAt,
    )

    fun body(
        header: MessageHeader,
        text: String,
    ) = MessageBody(header.ref, text, isTruncated = false)

    fun classification(
        key: String,
        importance: Importance = Importance.NORMAL,
        confidence: Float = 0.9f,
        reason: String = "because",
    ): AiClassification =
        AiClassification(folders.first { it.key == key }.target, importance, confidence, reason, summary = "sum")

    val thread =
        AiThread(
            "Trip",
            listOf(ThreadMessage(Address.of("ann@example.com"), 1, "Can we meet on Friday? I have news.")),
        )
}

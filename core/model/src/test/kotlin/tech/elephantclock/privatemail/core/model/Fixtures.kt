package tech.elephantclock.privatemail.core.model

object Fixtures {
    val accountId = AccountId("acc-1")

    val account =
        Account(
            id = accountId,
            email = Address.of("Nishad@Example.com", "Nishad"),
            displayName = "Work",
            username = "nishad@example.com",
            imap = ServerConfig("imap.example.com", 993, ConnectionSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.example.com", 587, ConnectionSecurity.STARTTLS),
            providerId = "fastmail",
        )

    val folder =
        MailFolder(
            accountId = accountId,
            path = "Money",
            displayName = "Money",
            uidValidity = 1_700_000_000,
            role = FolderRole.MONEY,
            description = "Invoices, payments, receipts",
        )

    val ref = MessageRef(accountId, "INBOX", uidValidity = 42, uid = 1001)

    val header =
        MessageHeader(
            ref = ref,
            messageId = "<abc@example.com>",
            from = Address.of("billing@shop.example", "Shop"),
            subject = "Your invoice",
            sentAtMillis = 1_760_000_000_000,
            receivedAtMillis = 1_760_000_001_000,
            to = listOf(Address.of("nishad@example.com")),
            inReplyTo = "<prev@example.com>",
            references = listOf("<root@example.com>", "<prev@example.com>"),
            flags = setOf(MessageFlag.SEEN),
            sizeBytes = 12_345,
            headers = mapOf("list-unsubscribe" to listOf("<mailto:u@shop.example>")),
        )

    val body =
        MessageBody(
            ref = ref,
            textPreview = "Invoice #12 — total €40",
            isTruncated = false,
            attachments = listOf(Attachment("2", "invoice.pdf", "application/pdf", 20_000)),
        )

    val signals =
        Signals(
            hasListUnsubscribe = true,
            esp = "mailgun",
            auth = AuthResults(spf = AuthResult.PASS, dkim = AuthResult.PASS, dmarc = AuthResult.FAIL),
            moneySignal = true,
        )

    val verdict =
        Verdict(
            importance = Importance.NORMAL,
            target = FolderTarget(FolderRole.MONEY),
            action = VerdictAction.FILE,
            source = VerdictSource.AI,
            reason = "Invoice from a shop",
            confidence = 0.82f,
            summary = "Invoice for €40.",
        )

    val rule =
        TriageRule(
            id = 7,
            accountId = null,
            matcher = RuleMatcher.Domain("appfigures.com"),
            action = VerdictAction.FILE,
            target = FolderTarget(FolderRole.REPORTS),
            createdAtMillis = 1_760_000_000_000,
        )
}

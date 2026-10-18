package tech.elephantclock.privatemail.core.triage.signals

import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.triage.MailItem

/**
 * Turns a message's headers and preview into [Signals]. Facts only, no decisions.
 *
 * Build one per account and reuse it: [frequentDomains] (domains the user often writes to,
 * from the relationship index) are preprocessed once. [trustedAuthservIds] are the user's
 * server's authserv-ids; when empty, the topmost `Authentication-Results` is trusted.
 *
 * Never throws, whatever the message contains.
 */
class SignalExtractor(
    frequentDomains: Collection<String> = emptyList(),
    private val trustedAuthservIds: Set<String> = emptySet(),
) {
    private val lookalike = LookalikeDomain(frequentDomains)

    fun extract(message: MailItem): Signals {
        val header = message.header
        val bulk = BulkDetector.detect(header)
        val from = header.from
        val content = contentText(message)
        return Signals(
            hasListUnsubscribe = bulk.hasListUnsubscribe,
            listId = bulk.listId,
            precedenceBulk = bulk.precedenceBulk,
            esp = bulk.esp,
            noReplySender = bulk.noReplySender,
            autoSubmitted = bulk.autoSubmitted,
            auth = AuthResultsParser.parse(header.headers["authentication-results"], trustedAuthservIds),
            hiddenCharacters =
                HiddenCharDetector.isSuspicious(header.subject) || HiddenCharDetector.isSuspicious(from?.name),
            lookalikeOf = from?.let { lookalike.detect(it.domain, it.name) },
            securityCode = Keywords.securityCode(content),
            moneySignal = Keywords.money(content),
            isReply = header.isReply,
        )
    }

    companion object {
        /** Subject and preview, joined, as the keyword detectors read them. */
        fun contentText(message: MailItem): String {
            val subject = Text.cap(message.header.subject, Text.MAX_HEADER)
            val preview = Text.cap(message.body?.textPreview.orEmpty(), Text.MAX_SCAN)
            return if (preview.isEmpty()) subject else Text.cap("$subject\n$preview", Text.MAX_SCAN)
        }
    }
}

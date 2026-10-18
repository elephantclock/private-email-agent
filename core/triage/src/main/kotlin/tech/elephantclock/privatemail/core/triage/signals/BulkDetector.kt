package tech.elephantclock.privatemail.core.triage.signals

import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageHeader
import java.util.Locale

/** Bulk-mail facts: list headers, `Precedence`, ESP fingerprints, no-reply senders, `Auto-Submitted`. */
object BulkDetector {
    data class Result(
        val hasListUnsubscribe: Boolean,
        val listId: String?,
        val precedenceBulk: Boolean,
        val esp: String?,
        val noReplySender: Boolean,
        val autoSubmitted: Boolean,
    )

    fun detect(header: MessageHeader): Result =
        Result(
            hasListUnsubscribe = !header.header("list-unsubscribe").isNullOrBlank(),
            listId = header.header("list-id")?.let(::listId),
            precedenceBulk = header.header("precedence")?.let(::isBulkPrecedence) ?: false,
            esp = esp(header),
            noReplySender = header.from?.let(::isNoReply) ?: false,
            autoSubmitted = header.header("auto-submitted")?.let(::isAutoSubmitted) ?: false,
        )

    /** The id inside `<...>` (RFC 2919), else the trimmed value; capped. */
    internal fun listId(raw: String): String? {
        val v = Text.cap(Text.unfold(raw), MAX_LIST_ID * 4)
        val open = v.lastIndexOf('<')
        val close = v.indexOf('>', open + 1)
        val id = (if (open >= 0 && close > open) v.substring(open + 1, close) else v).trim().lowercase(Locale.ROOT)
        return Text.cap(id, MAX_LIST_ID).takeIf { it.isNotEmpty() }
    }

    /** `bulk` and `junk` only: `list` is set by discussion lists the user may take part in. */
    internal fun isBulkPrecedence(raw: String): Boolean =
        raw.trim().lowercase(Locale.ROOT).let {
            it == "bulk" ||
                it == "junk"
        }

    /** RFC 3834: anything but `no` means a machine sent it. */
    internal fun isAutoSubmitted(raw: String): Boolean {
        val v =
            raw
                .trim()
                .lowercase(Locale.ROOT)
                .substringBefore(';')
                .trim()
        return v.isNotEmpty() && v != "no"
    }

    internal fun isNoReply(address: Address): Boolean {
        val local =
            address.localPart
                .substringBefore('+')
                .filter { it != '-' && it != '_' && it != '.' }
        return NO_REPLY_PREFIXES.any { local.startsWith(it) } || local in NO_REPLY_EXACT
    }

    /** ESP from its own headers, else from the bounce (`Return-Path`) domain. */
    internal fun esp(header: MessageHeader): String? {
        for ((name, esp) in ESP_HEADERS) {
            if (!header.header(name).isNullOrBlank()) return esp
        }
        header.header("feedback-id")?.lowercase(Locale.ROOT)?.let { fb ->
            FEEDBACK_ID_ESPS.firstOrNull { it.first in fb }?.let { return it.second }
        }
        val returnPath = header.header("return-path")?.let { Text.cap(it, Text.MAX_HEADER).lowercase(Locale.ROOT) }
        if (returnPath != null) {
            val domain = returnPath.substringAfterLast('@').trim().trimEnd('>', ' ', '.')
            RETURN_PATH_ESPS.firstOrNull { (suffix, _) -> domain == suffix || domain.endsWith(".$suffix") }?.let {
                return it.second
            }
        }
        return null
    }

    private const val MAX_LIST_ID = 255

    /** After removing `-`, `_` and `.`: English, Spanish, French, German and transliterated Russian. */
    private val NO_REPLY_PREFIXES =
        listOf("noreply", "donotreply", "dontreply", "norespond", "nepasrepondre", "nichtantworten", "neotvechat")
    private val NO_REPLY_EXACT = setOf("mailerdaemon")

    private val ESP_HEADERS =
        listOf(
            "x-mailgun-sid" to "mailgun",
            "x-sg-eid" to "sendgrid",
            "x-mc-user" to "mailchimp",
            "x-campaign" to "campaign",
        )

    private val FEEDBACK_ID_ESPS =
        listOf("amazonses" to "amazonses", "sendgrid" to "sendgrid", "mailchimp" to "mailchimp", "mailjet" to "mailjet")

    /** Bounce domains of common ESPs, including ones popular in Russia. */
    private val RETURN_PATH_ESPS =
        listOf(
            "mailgun.org" to "mailgun",
            "mailgun.net" to "mailgun",
            "sendgrid.net" to "sendgrid",
            "mcsv.net" to "mailchimp",
            "rsgsv.net" to "mailchimp",
            "mcdlv.net" to "mailchimp",
            "mandrillapp.com" to "mandrill",
            "amazonses.com" to "amazonses",
            "sparkpostmail.com" to "sparkpost",
            "mailjet.com" to "mailjet",
            "sendinblue.com" to "brevo",
            "brevo.com" to "brevo",
            "sendpulse.com" to "sendpulse",
            "unisender.com" to "unisender",
            "mindbox.ru" to "mindbox",
            "dashasender.ru" to "dashamail",
            "exacttarget.com" to "salesforce",
            "hubspotemail.net" to "hubspot",
            "klaviyomail.com" to "klaviyo",
        )
}

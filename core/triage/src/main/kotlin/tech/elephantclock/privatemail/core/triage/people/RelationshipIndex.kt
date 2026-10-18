package tech.elephantclock.privatemail.core.triage.people

import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.triage.signals.Domains
import java.util.Locale

/**
 * Who the user has a relationship with: addresses they have sent to (with counts and the last
 * date), threads they have written in (by their own Message-IDs), and their VIP list.
 *
 * Immutable; build it with [Builder] from the Sent folder's headers (or from stored stats).
 * Addresses are compared lower-case with any `+tag` removed, so mail to `anna+work@x` protects
 * replies from `anna@x`. The user's own addresses never count as "sent to": mail forged to look
 * like it came from the user is a common scam, and would otherwise be protected.
 */
class RelationshipIndex private constructor(
    private val sent: Map<String, SentStats>,
    private val ownMessageIds: Set<String>,
    private val vips: Set<String>,
) {
    data class SentStats(
        val count: Int,
        val lastSentMillis: Long,
    )

    fun sentStats(address: Address): SentStats? = sent[key(address.email)]

    fun hasSentTo(address: Address): Boolean = key(address.email) in sent

    fun isVip(address: Address): Boolean = key(address.email) in vips

    /** True when [header] replies to, or references, a message the user sent. */
    fun repliedInThread(header: MessageHeader): Boolean {
        if (ownMessageIds.isEmpty()) return false
        // In-Reply-To may list several ids ("<a@x> <b@y>").
        val ids =
            header.inReplyTo
                ?.take(MAX_ID * 4)
                ?.split(' ', '\t', '\r', '\n')
                .orEmpty()
                .asSequence() +
                header.references.asSequence().take(MAX_REFERENCES)
        return ids.any { id -> messageIdKey(id)?.let { it in ownMessageIds } == true }
    }

    /**
     * Registrable domains the user has sent to at least [minCount] times, most frequent first:
     * the "frequent domains" the look-alike check protects.
     */
    fun frequentDomains(
        minCount: Int = 2,
        limit: Int = 500,
    ): List<String> =
        sent
            .entries
            .groupingBy { Domains.registrable(it.key.substringAfterLast('@')) }
            .fold(0) { acc, e -> acc + e.value.count }
            .filter { it.value >= minCount && it.key.isNotEmpty() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }

    val sentToCount: Int get() = sent.size

    class Builder(
        ownAddresses: Collection<Address> = emptyList(),
    ) {
        private val own = ownAddresses.map { key(it.email) }.toSet()
        private val sent = HashMap<String, SentStats>()
        private val ownIds = HashSet<String>()
        private val vips = HashSet<String>()

        /** Adds one message from the Sent folder: its To and Cc recipients and its Message-ID. */
        fun addSent(header: MessageHeader): Builder {
            val at = header.sentAtMillis ?: header.receivedAtMillis
            (header.to + header.cc).map { key(it.email) }.distinct().forEach { addSentStats(it, 1, at) }
            header.messageId?.let(::addOwnMessageId)
            return this
        }

        /** Merges stored stats for one address (e.g. loaded from the database). */
        fun addSentStats(
            email: String,
            count: Int,
            lastSentMillis: Long,
        ): Builder {
            val k = key(email)
            if (count <= 0 || '@' !in k || k in own) return this
            sent.merge(k, SentStats(count, lastSentMillis)) { a, b ->
                SentStats(a.count + b.count, maxOf(a.lastSentMillis, b.lastSentMillis))
            }
            return this
        }

        fun addOwnMessageId(messageId: String): Builder {
            messageIdKey(messageId)?.let(ownIds::add)
            return this
        }

        fun addVip(email: String): Builder {
            val k = key(email)
            if ('@' in k) vips += k
            return this
        }

        fun build() = RelationshipIndex(sent.toMap(), ownIds.toSet(), vips.toSet())
    }

    companion object {
        val EMPTY = Builder().build()

        private const val MAX_REFERENCES = 100
        private const val MAX_ID = 998

        /** Lower-case, `+tag` removed from the local part. */
        internal fun key(email: String): String {
            val e = email.trim().lowercase(Locale.ROOT)
            val at = e.lastIndexOf('@')
            if (at <= 0) return e
            val local = e.substring(0, at).substringBefore('+').ifEmpty { e.substring(0, at) }
            return local + e.substring(at)
        }

        /** Message-ID without angle brackets or whitespace, lower-cased; null when empty or absurdly long. */
        internal fun messageIdKey(id: String): String? {
            val k =
                id
                    .trim()
                    .removePrefix("<")
                    .removeSuffix(">")
                    .trim()
                    .lowercase(Locale.ROOT)
            return k.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
        }
    }
}

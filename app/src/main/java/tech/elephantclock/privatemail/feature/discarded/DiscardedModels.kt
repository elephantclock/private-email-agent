package tech.elephantclock.privatemail.feature.discarded

import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.VerdictAction

/** One discarded or junked message as the digest sees it. */
data class DiscardedItem(
    val id: Long,
    val accountId: AccountId,
    /** The account's own address: the sender of a mailto unsubscribe. */
    val accountAddress: Address,
    val from: Address?,
    val subject: String,
    val receivedAtMillis: Long,
    val action: VerdictAction,
    val reason: String,
    val decidedAtMillis: Long,
    val unsubscribe: UnsubscribeMethod? = null,
)

/** The digest's tabs: discarded mail by sender or by reason, and junk (spam, phishing). */
enum class Grouping { SENDER, REASON, SPAM }

/**
 * Messages shown as one card. Actions apply to all of [ids]. Junk groups only restore: a
 * learned "never discard" would run before the phishing rules and let a fake sender through,
 * and unsubscribing from spam only confirms the address to the spammer.
 */
data class DiscardGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val ids: List<Long>,
    val accountId: AccountId,
    /** The one sender of a sender group; null for reason groups. */
    val sender: Address?,
    val junk: Boolean,
    val unsubscribe: UnsubscribeMethod?,
    val accountAddress: Address,
) {
    val count: Int get() = ids.size
    val canNeverDiscard: Boolean get() = sender != null && !junk
    val canUnsubscribe: Boolean get() = sender != null && !junk && unsubscribe != null
}

object DiscardGrouping {
    /** Groups [items] for [grouping], largest first; ties by the newest decision. */
    fun group(
        items: List<DiscardedItem>,
        grouping: Grouping,
    ): List<DiscardGroup> {
        val selected =
            items.filter { (it.action == VerdictAction.JUNK) == (grouping == Grouping.SPAM) }
        val buckets =
            selected.groupBy {
                when (grouping) {
                    Grouping.REASON -> "reason:${it.reason}"
                    Grouping.SENDER, Grouping.SPAM -> "sender:${it.accountId.value}:${it.from?.email.orEmpty()}"
                }
            }
        return buckets
            .map { (key, list) -> toGroup(key, list, grouping) }
            .sortedWith(compareByDescending<Pair<DiscardGroup, Long>> { it.first.count }.thenByDescending { it.second })
            .map { it.first }
    }

    private fun toGroup(
        key: String,
        list: List<DiscardedItem>,
        grouping: Grouping,
    ): Pair<DiscardGroup, Long> {
        val newest = list.maxBy { it.decidedAtMillis }
        val byReason = grouping == Grouping.REASON
        val senders = list.mapNotNull { it.from?.email }.distinct()
        val title =
            if (byReason) {
                newest.reason
            } else {
                newest.from?.let { it.name ?: it.email } ?: UNKNOWN_SENDER
            }
        val subtitle =
            if (byReason) {
                senders.take(2).joinToString(", ") + if (senders.size > 2) " +${senders.size - 2}" else ""
            } else {
                newest.reason
            }
        val group =
            DiscardGroup(
                key = key,
                title = title,
                subtitle = subtitle,
                ids = list.sortedByDescending { it.decidedAtMillis }.map { it.id },
                accountId = newest.accountId,
                sender = if (byReason) null else newest.from,
                junk = grouping == Grouping.SPAM,
                // The newest message's method: older ones may name an endpoint the sender has retired.
                unsubscribe =
                    if (byReason) {
                        null
                    } else {
                        list
                            .sortedByDescending { it.receivedAtMillis }
                            .first()
                            .unsubscribe
                    },
                accountAddress = newest.accountAddress,
            )
        return group to newest.decidedAtMillis
    }

    const val UNKNOWN_SENDER = "(no sender)"
}

/** What the confirmation dialog is about to do; nothing happens until the user confirms. */
sealed interface UnsubscribePlan {
    val groupKey: String
    val senderLabel: String

    data class OneClick(
        override val groupKey: String,
        override val senderLabel: String,
        val url: String,
        val host: String,
    ) : UnsubscribePlan

    data class Mail(
        override val groupKey: String,
        override val senderLabel: String,
        val accountId: AccountId,
        /** Exactly the message the dialog shows and the approval is created for. */
        val message: OutgoingMessage,
    ) : UnsubscribePlan
}

/** A one-line result shown under the header (restore, rule, unsubscribe). */
sealed interface DiscardNotice {
    data class Restored(
        val count: Int,
    ) : DiscardNotice

    data class RestoreFailed(
        val count: Int,
    ) : DiscardNotice

    data class NeverDiscard(
        val sender: String,
        val restored: Int,
    ) : DiscardNotice

    data class Unsubscribed(
        val sender: String,
    ) : DiscardNotice

    data class UnsubscribeRefused(
        val sender: String,
    ) : DiscardNotice

    data class UnsubscribeFailed(
        val sender: String,
    ) : DiscardNotice

    /** The connection dropped mid-send: it may or may not have arrived. */
    data class UnsubscribeUnknown(
        val sender: String,
    ) : DiscardNotice
}

data class DiscardedUiState(
    val loading: Boolean = true,
    val grouping: Grouping = Grouping.SENDER,
    val groups: List<DiscardGroup> = emptyList(),
    val discardedToday: Int = 0,
    val spamCount: Int = 0,
    /** Groups with an action running; their buttons are disabled. */
    val busy: Set<String> = emptySet(),
    /** Senders unsubscribed in this session; their Unsubscribe button is gone. */
    val unsubscribed: Set<String> = emptySet(),
    val confirm: UnsubscribePlan? = null,
    val notice: DiscardNotice? = null,
)

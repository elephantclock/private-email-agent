package tech.elephantclock.privatemail.core.data.triage

import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.SentFingerprint
import tech.elephantclock.privatemail.core.data.db.decodeAddresses
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.triage.people.RelationshipIndex
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds each account's [RelationshipIndex] from its stored Sent folder(s): every To and Cc
 * recipient with a count and the last date, and the Message-IDs of the user's own mail (for
 * "you replied in this thread"), plus the VIP list from the settings.
 *
 * Sent is synced like any folder (`MailRepository`), so the index covers what the sync has
 * stored: the last 30 days on a new account, growing from then on. The index is cached and
 * rebuilt only when the Sent rows, own addresses or VIPs change, so callers can ask per run.
 *
 * Contacts are not here: they come from the app's `ContactsSource` (see `TriageOrchestrator`).
 */
class RelationshipRepository(
    private val db: AppDatabase,
) {
    private data class Key(
        val sentFolders: List<String>,
        val fingerprint: SentFingerprint,
        val own: Set<String>,
        val vips: Set<String>,
    )

    private val cache = ConcurrentHashMap<AccountId, Pair<Key, RelationshipIndex>>()

    /**
     * The index for [account]. [ownAddresses] are the account's own address and aliases; they
     * never count as "sent to". The same instance is returned until something changes.
     */
    suspend fun index(
        account: AccountId,
        ownAddresses: Set<String>,
        vips: Set<String> = emptySet(),
    ): RelationshipIndex {
        val dao = db.triage()
        val folders = dao.sentFolders(account.value).sorted()
        val fingerprint =
            if (folders.isEmpty()) SentFingerprint(0, null) else dao.sentFingerprint(account.value, folders)
        val key = Key(folders, fingerprint, ownAddresses, vips)
        cache[account]?.let { (k, index) -> if (k == key) return index }

        val builder = RelationshipIndex.Builder(ownAddresses.mapNotNull(::address))
        if (folders.isNotEmpty()) {
            for (row in dao.sentRows(account.value, folders)) {
                val at = row.sentAtMillis ?: row.receivedAtMillis
                val recipients =
                    runCatching {
                        decodeAddresses(
                            row.toJson,
                        ) + decodeAddresses(row.ccJson)
                    }.getOrDefault(emptyList())
                recipients.map { it.email }.distinct().forEach { builder.addSentStats(it, 1, at) }
                row.messageIdHeader?.let(builder::addOwnMessageId)
            }
        }
        vips.forEach(builder::addVip)
        val index = builder.build()
        cache[account] = key to index
        return index
    }

    private fun address(email: String): Address? = runCatching { Address.of(email) }.getOrNull()
}

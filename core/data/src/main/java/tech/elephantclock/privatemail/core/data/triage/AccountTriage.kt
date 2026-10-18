package tech.elephantclock.privatemail.core.data.triage

import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.triage.people.ContactsSource
import tech.elephantclock.privatemail.core.triage.people.Protections
import tech.elephantclock.privatemail.core.triage.people.RelationshipIndex
import tech.elephantclock.privatemail.core.triage.rules.LearnedRules
import tech.elephantclock.privatemail.core.triage.rules.RulesEngine
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor
import java.util.concurrent.ConcurrentHashMap

/** Everything triage needs for one account during one drain. */
internal class AccountTriage(
    val account: Account,
    val createdAtMillis: Long,
    val settings: AccountTriageSettings,
    val engine: RulesEngine,
)

/**
 * Builds each account's [RulesEngine] and keeps it until its inputs change: the relationship
 * index (new Sent mail changes the frequent domains the look-alike check protects), learned
 * rules, trusted authserv-ids or the contacts source. Building is cheap but not free
 * ([SignalExtractor] precomputes look-alike skeletons), so it isn't done per message.
 */
internal class AccountTriageFactory(
    private val db: AppDatabase,
    private val relationships: RelationshipRepository,
    private val settings: TriageSettings,
    private val contacts: (AccountId) -> ContactsSource?,
) {
    private data class EngineKey(
        val index: RelationshipIndex,
        val rules: List<TriageRule>,
        val trustedAuthservIds: Set<String>,
        val contacts: ContactsSource?,
    )

    private val engines = ConcurrentHashMap<AccountId, Pair<EngineKey, RulesEngine>>()

    /** Null when the account no longer exists. */
    suspend fun prepare(accountId: String): AccountTriage? {
        val entity = db.accounts().get(accountId) ?: return null
        val account = entity.toModel()
        val s = settings.forAccount(account.id)
        val index = relationships.index(account.id, s.ownAddresses + account.email.email, s.vips)
        val rules = db.learnedRules().forAccount(accountId).map { it.toModel() }
        val key = EngineKey(index, rules, s.trustedAuthservIds, contacts(account.id))
        val engine =
            engines[account.id]?.takeIf { it.first == key }?.second
                ?: RulesEngine(
                    account = account.id,
                    protections = Protections(index, key.contacts),
                    extractor = SignalExtractor(index.frequentDomains(), s.trustedAuthservIds),
                    learned = LearnedRules(rules),
                ).also { engines[account.id] = key to it }
        return AccountTriage(account, entity.createdAtMillis, s, engine)
    }
}

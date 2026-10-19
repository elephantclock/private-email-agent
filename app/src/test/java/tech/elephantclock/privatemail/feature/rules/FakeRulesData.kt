package tech.elephantclock.privatemail.feature.rules

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.ImportPlan
import tech.elephantclock.privatemail.core.data.settings.RuleEditResult
import tech.elephantclock.privatemail.core.data.settings.RulesImport
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderTarget

val ACCOUNT_A = AccountId("a")
val ACCOUNT_B = AccountId("b")

/** In-memory [RulesData]: two accounts, prefs per account, a rule list and a gate status. */
class FakeRulesData : RulesData {
    val accounts =
        MutableStateFlow(
            listOf(RulesAccount(ACCOUNT_A, "alex@izi.example"), RulesAccount(ACCOUNT_B, "alex@ec.example")),
        )
    val prefs = MutableStateFlow(mapOf<AccountId, AccountPrefs>())
    val rules = MutableStateFlow(mapOf<AccountId, List<RuleRow>>())
    val digest = MutableStateFlow(true)
    val trash = MutableStateFlow(true)
    var gate = BackfillStatus.COMPLETE
    val retargets = mutableListOf<Pair<Long, FolderTarget>>()
    val deleted = mutableListOf<Long>()
    val imports = mutableListOf<Triple<ImportPlan, AccountId, Boolean>>()
    var exportBytes = "{}".encodeToByteArray()

    override fun accounts(): Flow<List<RulesAccount>> = accounts

    override fun prefs(id: AccountId): Flow<AccountPrefs> = prefs.map { it[id] ?: AccountPrefs() }

    override fun rules(id: AccountId): Flow<List<RuleRow>> = rules.map { it[id].orEmpty() }

    override fun digest(): Flow<Boolean> = digest

    override fun hasTrash(id: AccountId): Flow<Boolean> = trash

    override suspend fun gate(id: AccountId) = gate

    override suspend fun update(
        id: AccountId,
        transform: (AccountPrefs) -> AccountPrefs,
    ) {
        prefs.value = prefs.value + (id to transform(prefs.value[id] ?: AccountPrefs()))
    }

    override suspend fun setDigest(on: Boolean) {
        digest.value = on
    }

    override suspend fun deleteRule(id: Long) {
        deleted += id
    }

    override suspend fun retarget(
        id: Long,
        target: FolderTarget,
    ): RuleEditResult {
        val rule =
            rules.value.values
                .flatten()
                .firstOrNull { it.id == id } ?: return RuleEditResult.GONE
        if (rule.vip) return RuleEditResult.VIP_RULE
        retargets += id to target
        return RuleEditResult.DONE
    }

    override suspend fun export(id: AccountId) = exportBytes

    override fun parseImport(
        bytes: ByteArray,
        id: AccountId,
    ) = RulesImport.parse(bytes, id)

    override suspend fun applyImport(
        plan: ImportPlan,
        id: AccountId,
        trustAuthservIds: Boolean,
    ) {
        imports += Triple(plan, id, trustAuthservIds)
    }
}

class FakeRulesFiles : RulesFiles {
    val written = mutableMapOf<String, ByteArray>()
    val documents = mutableMapOf<String, ByteArray>()
    var failWrites = false

    override suspend fun write(
        uri: String,
        bytes: ByteArray,
    ): Boolean {
        if (failWrites) return false
        written[uri] = bytes
        return true
    }

    override suspend fun read(
        uri: String,
        max: Int,
    ): ByteArray? = documents[uri]?.let { it.copyOf(minOf(it.size, max + 1)) }
}

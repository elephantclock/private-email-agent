package tech.elephantclock.privatemail.feature.rules

import android.content.ContentResolver
import androidx.core.net.toUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.ImportPlan
import tech.elephantclock.privatemail.core.data.settings.ImportResult
import tech.elephantclock.privatemail.core.data.settings.LearnedRuleEdits
import tech.elephantclock.privatemail.core.data.settings.RuleEditResult
import tech.elephantclock.privatemail.core.data.settings.RulesExport
import tech.elephantclock.privatemail.core.data.settings.RulesImport
import tech.elephantclock.privatemail.core.data.settings.SentBackfillGate
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.RuleMatcher

/** What screen 07 reads and changes; [StoreRulesData] in the app, fakes in tests. */
interface RulesData {
    fun accounts(): Flow<List<RulesAccount>>

    fun prefs(id: AccountId): Flow<AccountPrefs>

    fun rules(id: AccountId): Flow<List<RuleRow>>

    fun digest(): Flow<Boolean>

    fun hasTrash(id: AccountId): Flow<Boolean>

    suspend fun gate(id: AccountId): BackfillStatus

    suspend fun update(
        id: AccountId,
        transform: (AccountPrefs) -> AccountPrefs,
    )

    suspend fun setDigest(on: Boolean)

    suspend fun deleteRule(id: Long)

    suspend fun retarget(
        id: Long,
        target: FolderTarget,
    ): RuleEditResult

    suspend fun export(id: AccountId): ByteArray

    fun parseImport(
        bytes: ByteArray,
        id: AccountId,
    ): ImportResult

    suspend fun applyImport(
        plan: ImportPlan,
        id: AccountId,
        trustAuthservIds: Boolean,
    )
}

/** Reads and writes the documents the system file picker chose. */
interface RulesFiles {
    /** False when the document couldn't be written. */
    suspend fun write(
        uri: String,
        bytes: ByteArray,
    ): Boolean

    /** At most [max] + 1 bytes (so the caller sees "too large" without reading a huge file); null when unreadable. */
    suspend fun read(
        uri: String,
        max: Int,
    ): ByteArray?
}

class StoreRulesData(
    private val db: AppDatabase,
    private val store: TriageSettingsStore,
    private val gate: SentBackfillGate,
    private val edits: LearnedRuleEdits = LearnedRuleEdits(db),
) : RulesData {
    override fun accounts(): Flow<List<RulesAccount>> =
        db.accounts().all().map { list -> list.map { RulesAccount(AccountId(it.id), it.email) } }

    override fun prefs(id: AccountId) = store.account(id)

    override fun rules(id: AccountId): Flow<List<RuleRow>> =
        db.rules().rules(id.value).map { list ->
            list.mapNotNull { e ->
                val rule = runCatching { e.toModel() }.getOrNull() ?: return@mapNotNull null
                val who =
                    when (val m = rule.matcher) {
                        is RuleMatcher.Sender -> m.email
                        is RuleMatcher.Domain -> m.domain
                    }
                RuleRow(rule.id, who, rule.target, rule.vip, allAccounts = rule.accountId == null)
            }
        }

    override fun digest() = store.digestEnabled

    override fun hasTrash(id: AccountId) = db.rules().hasTrash(id.value)

    override suspend fun gate(id: AccountId) = gate.status(id)

    override suspend fun update(
        id: AccountId,
        transform: (AccountPrefs) -> AccountPrefs,
    ) {
        store.update(id, transform)
    }

    override suspend fun setDigest(on: Boolean) = store.setDigest(on)

    override suspend fun deleteRule(id: Long) = edits.delete(id)

    override suspend fun retarget(
        id: Long,
        target: FolderTarget,
    ) = edits.retarget(id, target)

    override suspend fun export(id: AccountId) = RulesExport.encode(RulesExport.build(db, store, id))

    override fun parseImport(
        bytes: ByteArray,
        id: AccountId,
    ) = RulesImport.parse(bytes, id)

    override suspend fun applyImport(
        plan: ImportPlan,
        id: AccountId,
        trustAuthservIds: Boolean,
    ) = plan.apply(db, store, id, trustAuthservIds)
}

/** SAF documents through the [resolver]; reads are capped, whatever size the provider reports. */
class ContentRulesFiles(
    private val resolver: ContentResolver,
) : RulesFiles {
    override suspend fun write(
        uri: String,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            // "wt": truncate, so a shorter export never leaves the tail of an older file behind.
            resolver.openOutputStream(uri.toUri(), "wt")?.use { it.write(bytes) } != null
        }.getOrDefault(false)

    override suspend fun read(
        uri: String,
        max: Int,
    ): ByteArray? =
        runCatching {
            resolver.openInputStream(uri.toUri())?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (out.size() <= max) {
                    val n = input.read(buffer, 0, minOf(buffer.size, max + 1 - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull()
}

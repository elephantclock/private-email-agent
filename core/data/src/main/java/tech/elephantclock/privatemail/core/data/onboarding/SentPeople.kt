package tech.elephantclock.privatemail.core.data.onboarding

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.SentRow
import tech.elephantclock.privatemail.core.data.db.decodeAddresses
import tech.elephantclock.privatemail.core.model.AccountId
import java.util.Locale

/** Someone the user writes to, from the Sent folder (screen 02's people list). */
data class SentPerson(
    val email: String,
    val name: String?,
    val count: Int,
    val lastSentMillis: Long,
)

/**
 * The people the user writes to, most written-to first, recomputed whenever the Sent rows
 * change (the first sync, then the 12-month backfill). The account's own addresses are left out.
 * Addresses are grouped like the relationship index does: lower case, `+tag` removed.
 */
class SentPeople(
    private val db: AppDatabase,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun people(
        id: AccountId,
        own: Set<String>,
    ): Flow<List<SentPerson>> =
        db
            .firstRun()
            .sentFingerprint(id.value)
            .distinctUntilChanged()
            .mapLatest {
                val paths = db.triage().sentFolders(id.value)
                if (paths.isEmpty()) emptyList() else aggregate(db.triage().sentRows(id.value, paths), own)
            }.flowOn(work)

    companion object {
        fun aggregate(
            rows: List<SentRow>,
            own: Set<String>,
        ): List<SentPerson> {
            val ownKeys = own.map(::key).toSet()
            val byKey = HashMap<String, SentPerson>()
            for (row in rows) {
                val at = row.sentAtMillis ?: row.receivedAtMillis
                val recipients =
                    runCatching { decodeAddresses(row.toJson) + decodeAddresses(row.ccJson) }.getOrDefault(emptyList())
                for (a in recipients.distinctBy { key(it.email) }) {
                    val k = key(a.email)
                    if (k in ownKeys) continue
                    val prev = byKey[k]
                    byKey[k] =
                        if (prev == null) {
                            SentPerson(k, a.name, 1, at)
                        } else {
                            // The name from the latest message wins; a missing name never replaces one.
                            val newer = at >= prev.lastSentMillis
                            SentPerson(
                                k,
                                if (newer) a.name ?: prev.name else prev.name ?: a.name,
                                prev.count + 1,
                                maxOf(at, prev.lastSentMillis),
                            )
                        }
                }
            }
            return byKey.values.sortedWith(
                compareByDescending<SentPerson> { it.count }.thenByDescending { it.lastSentMillis }.thenBy { it.email },
            )
        }

        /** Lower case, `+tag` removed (as `RelationshipIndex` compares addresses). */
        fun key(email: String): String {
            val e = email.trim().lowercase(Locale.ROOT)
            val at = e.lastIndexOf('@')
            if (at <= 0) return e
            val local = e.substring(0, at).substringBefore('+').ifEmpty { e.substring(0, at) }
            return local + e.substring(at)
        }
    }
}

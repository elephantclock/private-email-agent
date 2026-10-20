package tech.elephantclock.privatemail.core.data.repo

import android.database.Cursor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tech.elephantclock.privatemail.core.data.db.AppDatabase

/**
 * Identifies one state of a thread: its root Message-ID and its latest message. A new message in
 * the thread changes [latest], so a cached brief for the old state is never shown for the new one.
 */
data class BriefKey(
    val accountId: String,
    val root: String,
    val latest: String,
) {
    val value: String get() = "$accountId|$root|$latest"
}

/** One message of the thread as the model gets it: quoted history removed. */
data class BriefMessage(
    val localId: Long,
    val fromEmail: String?,
    val fromName: String?,
    val sentAtMillis: Long?,
    val text: String,
    /** Written by the account's owner (a reply in Sent). */
    val fromUser: Boolean,
)

/** The thread to summarise, oldest message first. */
data class BriefThread(
    val key: BriefKey,
    val subject: String,
    val messages: List<BriefMessage>,
)

/** The brief, in storage terms (core:data doesn't depend on core:ai; the app maps `ThreadSummary`). */
data class ThreadBrief(
    val gist: String,
    /** `BriefStatus` key from core:ai, e.g. "waiting_on_you"; null when unknown. */
    val status: String? = null,
    /** YYYY-MM-DD. */
    val deadline: String? = null,
    val nextSteps: List<String> = emptyList(),
)

enum class BriefFailure { UNAVAILABLE, TIMEOUT, REFUSED, INVALID, ERROR, NO_MESSAGE }

sealed interface BriefOutcome {
    data class Ready(
        val brief: ThreadBrief,
        val cached: Boolean = false,
    ) : BriefOutcome

    data class Failed(
        val failure: BriefFailure,
    ) : BriefOutcome
}

/**
 * Thread briefs (T32): finds the open message's thread, prepares its text and caches the model's
 * answer per thread state ([BriefKey]). The model call itself is the caller's [compute] (the app
 * runs it through `AiQueue.foreground` and `SafeAiEngine`), so this module stays free of core:ai.
 *
 * - **Thread:** root = first References entry, else In-Reply-To, else the message's own
 *   Message-ID. Members: same account, Message-ID equal to the root or one of the open message's
 *   references, or replying to / referencing the root (SQL `LIKE` as a prefilter, then an exact
 *   check on the decoded list). Copies in several folders (INBOX and Sent, Gmail's All Mail)
 *   count once. A message without any ids is a thread of one (`local:<id>`).
 * - **Text:** the newest [fullTextMessages] messages use the full text from the server
 *   ([brief]'s `fullText`, one IMAP sign-in each), older ones the stored preview; every text goes
 *   through [QuoteStripper]. A failed fetch falls back to the preview.
 * - **Cache:** successful briefs only, in memory, LRU of [cacheSize]. Failures aren't cached, so
 *   a timeout or a background-blocked call is retried next time. Not on disk: a brief is cheap to
 *   recompute compared with adding a second store of mail content to back up and wipe.
 * - **Invalidation:** [keys] re-reads the key whenever the messages table changes and emits only
 *   when it differs, i.e. when a message joins or leaves the thread; storing a brief for a newer
 *   state drops the older one of the same thread.
 */
class BriefRepository(
    private val db: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cacheSize: Int = 64,
    private val fullTextMessages: Int = 2,
    private val maxMessages: Int = 30,
) {
    private val cache =
        object : LinkedHashMap<String, ThreadBrief>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ThreadBrief>?) = size > cacheSize
        }

    /** The thread key of [messageId], again whenever the thread gains or loses a message. */
    fun keys(messageId: Long): Flow<BriefKey?> =
        db.invalidationTracker
            .createFlow("messages")
            .map { key(messageId) }
            .distinctUntilChanged()

    suspend fun key(messageId: Long): BriefKey? = withContext(io) { members(messageId)?.let { (key, _) -> key } }

    fun cached(key: BriefKey): ThreadBrief? = synchronized(cache) { cache[key.value] }

    fun clear() = synchronized(cache) { cache.clear() }

    /**
     * The brief for [messageId]'s thread: from the cache, else [compute] on the prepared thread.
     * [fullText] returns a message's full plain text, or null when it can't be fetched.
     */
    suspend fun brief(
        messageId: Long,
        fullText: suspend (Long) -> String?,
        compute: suspend (BriefThread) -> BriefOutcome,
    ): BriefOutcome {
        // The key needs only the database: a cached brief costs no IMAP sign-in.
        val key = key(messageId) ?: return BriefOutcome.Failed(BriefFailure.NO_MESSAGE)
        cached(key)?.let { return BriefOutcome.Ready(it, cached = true) }
        val thread = thread(messageId, fullText) ?: return BriefOutcome.Failed(BriefFailure.NO_MESSAGE)
        val outcome = compute(thread)
        if (outcome is BriefOutcome.Ready) put(thread.key, outcome.brief)
        return outcome
    }

    /** The prepared thread (texts fetched and stripped), or null when the message is gone. */
    suspend fun thread(
        messageId: Long,
        fullText: suspend (Long) -> String?,
    ): BriefThread? {
        val (key, rows) = withContext(io) { members(messageId) } ?: return null
        val ownEmail =
            withContext(io) {
                db
                    .accounts()
                    .get(key.accountId)
                    ?.email
                    ?.lowercase()
            }
        val messages =
            rows.mapIndexedNotNull { i, row ->
                val newest = i >= rows.size - fullTextMessages
                val raw = (if (newest) fullText(row.id) else null) ?: row.snippet
                val text = QuoteStripper.strip(raw)
                if (text.isBlank()) return@mapIndexedNotNull null
                BriefMessage(
                    localId = row.id,
                    fromEmail = row.fromEmail,
                    fromName = row.fromName,
                    sentAtMillis = row.sentAtMillis ?: row.receivedAtMillis,
                    text = text,
                    fromUser = ownEmail != null && row.fromEmail?.lowercase() == ownEmail,
                )
            }
        if (messages.isEmpty()) return null
        val subject = rows.first { it.id == messageId }.subject
        return BriefThread(key, subject, messages)
    }

    private fun put(
        key: BriefKey,
        brief: ThreadBrief,
    ) = synchronized(cache) {
        val prefix = "${key.accountId}|${key.root}|"
        cache.keys.removeAll { it.startsWith(prefix) && it != key.value }
        cache[key.value] = brief
    }

    private class Row(
        val id: Long,
        val accountId: String,
        val messageIdHeader: String?,
        val fromEmail: String?,
        val fromName: String?,
        val subject: String,
        val sentAtMillis: Long?,
        val receivedAtMillis: Long,
        val inReplyTo: String?,
        val references: List<String>,
        val snippet: String,
    ) {
        val time: Long get() = sentAtMillis ?: receivedAtMillis
    }

    /** The thread's key and its members, oldest first (at most [maxMessages], the newest kept). */
    private fun members(messageId: Long): Pair<BriefKey, List<Row>>? {
        val open = rows("SELECT $COLUMNS FROM messages WHERE id = ?", arrayOf(messageId)).firstOrNull() ?: return null
        val root = open.references.firstOrNull() ?: open.inReplyTo ?: open.messageIdHeader
        val found =
            if (root == null) {
                listOf(open)
            } else {
                val ancestors = (open.references + listOfNotNull(open.inReplyTo, root)).distinct().take(MAX_IDS)
                val marks = ancestors.joinToString(",") { "?" }
                val args = ArrayList<Any?>()
                args += open.accountId
                args.addAll(ancestors)
                args += root
                args += "%" + likeEscape(jsonString(root)) + "%"
                val sql =
                    "SELECT $COLUMNS FROM messages WHERE accountId = ? AND (messageIdHeader IN ($marks) " +
                        "OR inReplyTo = ? OR referencesJson LIKE ? ESCAPE '\\') ORDER BY receivedAtMillis DESC LIMIT $SCAN_LIMIT"
                // The open message first, so it is the copy kept when several share a Message-ID.
                listOf(open) +
                    rows(sql, args.toTypedArray())
                        .filter { r -> r.messageIdHeader in ancestors || r.inReplyTo == root || root in r.references }
                        .sortedBy { it.id } // of several copies, the first stored one is kept
            }
        val unique =
            found
                .distinctBy { it.messageIdHeader ?: "local:${it.id}" }
                .sortedWith(compareBy<Row>({ it.time }, { it.id }))
                .takeLast(maxMessages)
                .let { if (it.any { r -> r.id == open.id }) it else (it.drop(1) + open).sortedBy { r -> r.time } }
        val latest = unique.last()
        val key = BriefKey(open.accountId, root ?: "local:${open.id}", latest.messageIdHeader ?: "local:${latest.id}")
        return key to unique
    }

    private fun rows(
        sql: String,
        args: Array<Any?>,
    ): List<Row> =
        db.query(sql, args).use { c ->
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) out += c.row()
            out
        }

    private fun Cursor.row() =
        Row(
            id = getLong(0),
            accountId = getString(1),
            messageIdHeader = if (isNull(2)) null else getString(2),
            fromEmail = if (isNull(3)) null else getString(3),
            fromName = if (isNull(4)) null else getString(4),
            subject = getString(5),
            sentAtMillis = if (isNull(6)) null else getLong(6),
            receivedAtMillis = getLong(7),
            inReplyTo = if (isNull(8)) null else getString(8),
            references = decodeReferences(getString(9)),
            snippet = getString(10),
        )

    private companion object {
        const val COLUMNS =
            "id, accountId, messageIdHeader, fromEmail, fromName, subject, sentAtMillis, receivedAtMillis, " +
                "inReplyTo, referencesJson, snippet"
        const val MAX_IDS = 25
        const val SCAN_LIMIT = 200
        val json = Json { ignoreUnknownKeys = true }

        fun decodeReferences(value: String): List<String> =
            try {
                json.decodeFromString<List<String>>(value)
            } catch (_: Exception) {
                emptyList()
            }

        /** The JSON form of [s] as it appears inside `referencesJson`, quotes included. */
        fun jsonString(s: String): String = json.encodeToString(s)

        fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

package tech.elephantclock.privatemail.feature.reply

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.ThreadMessage
import tech.elephantclock.privatemail.core.ai.safety.Redactor
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.model.Address

/** What a reply draft is written from: the message answered and a sample of the user's writing. */
data class ReplyMaterial(
    val thread: AiThread,
    val styleSample: String?,
)

fun interface ReplyAiSource {
    suspend fun material(parentId: Long): ReplyMaterial?
}

val NoReplyAiSource = ReplyAiSource { null }

/**
 * Reads the parent's stored snippet (the first 4 KB of its text, from sync) and the newest
 * snippets in the account's Sent folder. Read-only; no server access. The Sent query is raw SQL
 * because `core:data` has no DAO for it yet (candidate for `ReadingDao`).
 */
class RoomReplyAiSource(
    private val db: AppDatabase,
) : ReplyAiSource {
    override suspend fun material(parentId: Long): ReplyMaterial? =
        withContext(Dispatchers.IO) {
            val parent = db.messages().get(parentId) ?: return@withContext null
            val from = parent.fromEmail?.let { runCatching { Address.of(it, parent.fromName) }.getOrNull() }
            val thread =
                AiThread(
                    parent.subject,
                    listOf(
                        ThreadMessage(
                            from,
                            parent.sentAtMillis,
                            QuoteText.strip(parent.snippet).ifBlank { parent.snippet },
                        ),
                    ),
                )
            ReplyMaterial(thread, StyleSample.select(sentSnippets(parent.accountId)))
        }

    private fun sentSnippets(accountId: String): List<String> {
        val query =
            SimpleSQLiteQuery(
                "SELECT m.snippet FROM messages m JOIN folders f " +
                    "ON f.accountId = m.accountId AND f.path = m.folderPath " +
                    "WHERE m.accountId = ? AND f.specialUse = 'SENT' AND m.snippet != '' " +
                    "ORDER BY m.receivedAtMillis DESC LIMIT ?",
                arrayOf<Any>(accountId, StyleSample.CANDIDATES),
            )
        return db.query(query).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }
}

/**
 * A short sample of how the user writes, from their own sent mail: quoted history and signatures
 * removed, at most [MAX_SNIPPETS] snippets of [SNIPPET_CHARS], [MAX_CHARS] in all, and redacted
 * (codes, cards, tokens never reach the prompt even from the user's own mail).
 */
object StyleSample {
    const val CANDIDATES = 8
    const val MAX_SNIPPETS = 3
    const val SNIPPET_CHARS = 300
    const val MAX_CHARS = 1_000
    private const val MIN_CHARS = 20

    fun select(
        sentNewestFirst: List<String>,
        redactor: Redactor = Redactor(),
    ): String? {
        val picked =
            sentNewestFirst
                .asSequence()
                .map { QuoteText.strip(QuoteText.dropSignature(it)).trim() }
                .filter { it.length >= MIN_CHARS }
                .map { it.take(SNIPPET_CHARS).trim() }
                .distinct()
                .take(MAX_SNIPPETS)
                .toList()
        if (picked.isEmpty()) return null
        return redactor.redact(picked.joinToString("\n---\n").take(MAX_CHARS))
    }
}

/** Removes quoted history from a message's text: what the sender wrote is what's left. */
object QuoteText {
    private val cutLines =
        listOf(
            Regex("^On .{1,200} wrote:$"),
            Regex("^.{1,200} (пишет|писал|написал|написала|wrote|schrieb|a écrit|escribió)\\s?:$"),
            Regex("^-{2,} ?(Original Message|Forwarded message|Исходное сообщение|Пересылаемое сообщение) ?-{2,}$"),
            Regex("^(From|От): .{1,200}$"),
        )

    fun strip(text: String): String {
        val kept = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (cutLines.any { it.matches(line) }) break
            if (line.startsWith(">")) continue
            kept += raw
        }
        return kept.joinToString("\n").trim()
    }

    /** Everything after the "-- " signature separator. */
    fun dropSignature(text: String): String = text.lines().takeWhile { it != "-- " && it != "--" }.joinToString("\n")
}

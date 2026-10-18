package tech.elephantclock.privatemail.feature.inbox

import tech.elephantclock.privatemail.core.data.db.MessageWithVerdict
import tech.elephantclock.privatemail.core.data.db.toFlags
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.ui.components.ImportanceLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The T02 pill shows the domain [Importance] one to one (the carried-over T44 mapping). */
fun Importance.toLevel(): ImportanceLevel =
    when (this) {
        Importance.URGENT -> ImportanceLevel.Urgent
        Importance.HIGH -> ImportanceLevel.High
        Importance.NORMAL -> ImportanceLevel.Normal
        Importance.LOW -> ImportanceLevel.Low
    }

/** An account as the filter chips show it. [colorIndex] picks its dot colour ([accountColor]). */
data class AccountChip(
    val id: String,
    val label: String,
    val colorIndex: Int,
    val priorityCount: Int = 0,
)

/** One line in a message list. Built from stored data only: nothing here comes from the network. */
data class MessageRow(
    val id: Long,
    val accountId: String,
    val sender: String,
    val subject: String,
    /** The AI summary when there is one, else the start of the stored preview. */
    val preview: String,
    val time: String,
    /** Null for messages triage hasn't seen yet (shown without a pill). */
    val level: ImportanceLevel?,
    val unread: Boolean,
    val hasAttachments: Boolean,
)

/** A synced folder chip in the Folders list. */
data class FolderItem(
    val path: String,
    val label: String,
    val total: Int,
    val unread: Int,
)

/** "Today: 38 filed, 61 discarded." [labelOnly] = some of them are still in the server's Inbox. */
data class TodaySummary(
    val filed: Int,
    val discarded: Int,
    val labelOnly: Boolean,
)

/**
 * Formats list times: "15:02" for today, "12 Oct" this year, else a short date. The clock and
 * zone are injected so tests and screenshots are stable.
 */
class TimeLabels(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
) {
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val full = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val dateTime =
        DateTimeFormatter
            .ofLocalizedDateTime(
                FormatStyle.MEDIUM,
                FormatStyle.SHORT,
            ).withLocale(locale)

    fun short(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        return when {
            at.toLocalDate() == today -> time.format(at)
            at.year == today.year -> dayMonth.format(at)
            else -> full.format(at)
        }
    }

    /** For the detail screen's header line. */
    fun long(millis: Long): String = dateTime.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Midnight at the start of today, for "filed today". */
    fun startOfToday(): Long =
        Instant
            .ofEpochMilli(clock())
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
}

private const val PREVIEW_CHARS = 160

fun MessageWithVerdict.toRow(times: TimeLabels): MessageRow {
    val m = message
    val sender = m.fromName?.takeIf { it.isNotBlank() } ?: m.fromEmail ?: ""
    val preview =
        verdict?.summary?.takeIf { it.isNotBlank() }
            ?: m.snippet
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .take(PREVIEW_CHARS)
    return MessageRow(
        id = m.id,
        accountId = m.accountId,
        sender = sender,
        subject = m.subject,
        preview = preview,
        time = times.short(m.receivedAtMillis),
        level = verdict?.importance?.toLevel(),
        unread = MessageFlag.SEEN !in m.flags.toFlags(),
        hasAttachments = m.hasAttachments,
    )
}

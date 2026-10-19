package tech.elephantclock.privatemail.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tech.elephantclock.privatemail.PrivateMailApp
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.notify.DigestNotification
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** What triage did today, for the 18:00 digest. */
data class DigestSummary(
    val filed: Int = 0,
    val discarded: Int = 0,
    val junk: Int = 0,
    /** Unread URGENT/HIGH mail still in the Inbox. */
    val needsYou: Int = 0,
    /** Unread mail whose alert was held back because it failed sender authentication. */
    val unverified: Int = 0,
) {
    val isEmpty: Boolean get() = filed + discarded + junk + needsYou + unverified == 0
}

fun interface DigestSource {
    suspend fun summary(
        fromMillis: Long,
        toMillis: Long,
    ): DigestSummary
}

/** Reads the day's verdicts from Room (no server access). */
class RoomDigestSource(
    private val db: AppDatabase,
) : DigestSource {
    override suspend fun summary(
        fromMillis: Long,
        toMillis: Long,
    ): DigestSummary {
        val counts = db.discarded().countsBetween(fromMillis, toMillis).associate { it.action to it.count }
        return DigestSummary(
            filed = counts[VerdictAction.FILE] ?: 0,
            discarded = counts[VerdictAction.DISCARD] ?: 0,
            junk = counts[VerdictAction.JUNK] ?: 0,
            needsYou = db.discarded().importantUnreadBetween(fromMillis, toMillis),
            unverified =
                db
                    .discarded()
                    .suppressedAlerts()
                    .first()
                    .size,
        )
    }
}

/**
 * The daily digest (T49): at 18:00 local time, one quiet notification with what triage did
 * since midnight. Reads the database only; nothing is sent or moved. A one-time request that
 * schedules the next one when it runs (a 24-hour periodic request drifts and is wrong across
 * daylight-saving changes). Posts nothing on an empty day or without the permission.
 */
class DigestWorker(
    context: Context,
    params: WorkerParameters,
    private val source: DigestSource?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val post: (Context, DigestSummary) -> Unit = DigestNotification::post,
) : CoroutineWorker(context, params) {
    /** For WorkManager's default factory: the database comes from the app's graph. */
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        (context.applicationContext as? PrivateMailApp)?.graph?.database?.let(::RoomDigestSource),
    )

    override suspend fun doWork(): Result {
        val now = Instant.ofEpochMilli(clock()).atZone(zone())
        try {
            val summary = source?.summary(startOfDay(now), now.toInstant().toEpochMilli())
            if (summary != null && !summary.isEmpty) post(applicationContext, summary)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A missed digest is not worth a retry storm; tomorrow's runs as usual.
        } finally {
            // Queued behind this run, so it starts only once this one has finished.
            WorkManager
                .getInstance(applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(delayUntilNext(now)))
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "daily-digest"
        val AT: LocalTime = LocalTime.of(18, 0)

        /** Time from [now] to the next 18:00 strictly after it, in [now]'s zone (DST-aware). */
        fun delayUntilNext(now: ZonedDateTime): Duration {
            val today = now.toLocalDate().atTime(AT).atZone(now.zone)
            val next =
                if (today.isAfter(now)) {
                    today
                } else {
                    now
                        .toLocalDate()
                        .plusDays(1)
                        .atTime(AT)
                        .atZone(now.zone)
                }
            return Duration.between(now, next)
        }

        fun startOfDay(now: ZonedDateTime): Long =
            now
                .toLocalDate()
                .atStartOfDay(now.zone)
                .toInstant()
                .toEpochMilli()

        fun request(delay: Duration): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DigestWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()

        /** Idempotent: an already scheduled digest keeps its time. Call at app start. */
        fun schedule(
            workManager: WorkManager,
            now: ZonedDateTime = ZonedDateTime.now(),
        ) {
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request(delayUntilNext(now)))
        }
    }
}

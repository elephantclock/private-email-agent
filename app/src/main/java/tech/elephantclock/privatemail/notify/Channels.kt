package tech.elephantclock.privatemail.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.model.Importance

/**
 * The app's notification channels (T49). Their importance and sound are fixed once created
 * (Android lets only the user change them afterwards), so the ids carry a version suffix:
 * changing a channel's behaviour means a new id.
 *
 * - **Urgent:** heads-up with the default sound (VIPs, security codes, duplicate charges).
 * - **High:** shown in the shade and status bar, silent, no vibration.
 * - **Daily digest:** low, once a day at 18:00.
 */
object Channels {
    const val URGENT = "triage_urgent_v1"
    const val HIGH = "triage_high_v1"
    const val DIGEST = "daily_digest_v1"

    /** Idempotent; call before posting (re-creating only updates the names). */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val urgent =
            NotificationChannel(
                URGENT,
                context.getString(R.string.notify_channel_urgent),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notify_channel_urgent_description)
                enableVibration(true)
            }
        val high =
            NotificationChannel(
                HIGH,
                context.getString(R.string.notify_channel_high),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notify_channel_high_description)
                setSound(null, null)
                enableVibration(false)
            }
        val digest =
            NotificationChannel(
                DIGEST,
                context.getString(R.string.notify_channel_digest),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notify_channel_digest_description) }
        manager.createNotificationChannels(listOf(urgent, high, digest))
    }

    /** The channel for an alert; only URGENT and HIGH notify at all. */
    fun forImportance(importance: Importance): String? =
        when (importance) {
            Importance.URGENT -> URGENT
            Importance.HIGH -> HIGH
            Importance.NORMAL, Importance.LOW -> null
        }
}

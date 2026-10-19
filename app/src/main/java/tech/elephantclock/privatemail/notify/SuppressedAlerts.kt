package tech.elephantclock.privatemail.notify

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.SuppressedAlertRow

/**
 * Mail that would have alerted but failed SPF/DKIM/DMARC (T24 lowers it to NORMAL, so a VIP
 * writing through a mailing list that breaks DKIM gets no notification). Instead of silence
 * the inbox shows a badge: "2 important-looking messages failed sender checks". Counts unread
 * Inbox mail only, so reading or filing them clears the badge.
 */
class SuppressedAlerts(
    private val db: AppDatabase,
) {
    fun items(): Flow<List<SuppressedAlertRow>> = db.discarded().suppressedAlerts()

    fun count(): Flow<Int> = items().map { it.size }.distinctUntilChanged()
}

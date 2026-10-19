package tech.elephantclock.privatemail.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.MailGraph
import tech.elephantclock.privatemail.MainActivity
import tech.elephantclock.privatemail.core.data.db.FolderEntity
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.triage.TriageAlert
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.ServerConfig
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.notificationArchiver
import tech.elephantclock.privatemail.triageNotifier

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val account = AccountId("acc-1")

    private fun alert(
        localId: Long = 42,
        importance: Importance = Importance.URGENT,
    ) = TriageAlert(
        account,
        localId,
        Address.of("sarah@bigclient.example", "Sarah Chen"),
        "Contract signed today?",
        importance,
        "VIP sender",
    )

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun `channels - urgent sounds, high is silent, digest is low`() {
        Channels.ensure(app)
        val urgent = manager.getNotificationChannel(Channels.URGENT)
        val high = manager.getNotificationChannel(Channels.HIGH)
        val digest = manager.getNotificationChannel(Channels.DIGEST)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, urgent.importance)
        assertNotNull(urgent.sound)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, high.importance)
        assertNull(high.sound)
        assertFalse(high.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_LOW, digest.importance)
        Channels.ensure(app) // idempotent
        assertEquals(3, manager.notificationChannels.size)
    }

    @Test
    fun `an urgent alert posts sender, subject and Open and Draft reply`() {
        grant()
        AndroidTriageNotifier(app).notify(alert())

        val n = posted().single()
        assertEquals(Channels.URGENT, n.channelId)
        assertEquals("Sarah Chen", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Contract signed today?", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.CATEGORY_EMAIL, n.category)
        assertEquals(listOf("Open", "Draft reply"), n.actions.map { it.title.toString() })
        // Lock screen: private, with a generic public version.
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("New important email", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))

        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals(ComponentName(app, MainActivity::class.java), open.component)
        assertTrue(shadowOf(n.contentIntent).isActivityIntent)
        assertEquals(NotificationLink.Open(account, 42), NotificationLinks.parse(open))
        val reply = shadowOf(n.actions[1].actionIntent).savedIntent
        assertTrue(shadowOf(n.actions[1].actionIntent).isActivityIntent)
        assertEquals(NotificationLink.DraftReply(account, 42), NotificationLinks.parse(reply))
        assertTrue(shadowOf(n.contentIntent).isImmutable)
    }

    @Test
    fun `high alerts use the silent channel, normal ones post nothing`() {
        grant()
        val notifier = AndroidTriageNotifier(app)
        notifier.notify(alert(importance = Importance.NORMAL))
        assertTrue(posted().isEmpty())
        notifier.notify(alert(importance = Importance.HIGH))
        assertEquals(Channels.HIGH, posted().single().channelId)
    }

    @Test
    fun `nothing is posted without the permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidTriageNotifier(app).notify(alert())
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `each message gets its own notification and its own intents`() {
        grant()
        val notifier = AndroidTriageNotifier(app)
        notifier.notify(alert(localId = 1))
        notifier.notify(alert(localId = 2))
        val (a, b) = posted()
        assertNotEquals(shadowOf(a.contentIntent).savedIntent.data, shadowOf(b.contentIntent).savedIntent.data)
        assertEquals(
            setOf(1L, 2L),
            posted()
                .map {
                    (NotificationLinks.parse(shadowOf(it.contentIntent).savedIntent) as NotificationLink.Open).localId
                }.toSet(),
        )
    }

    @Test
    fun `Archive is not offered for an account that can't archive`() {
        grant()
        AndroidTriageNotifier(app, canArchive = { it != account }).notify(alert())
        assertEquals(listOf("Open", "Draft reply"), posted().single().actions.map { it.title.toString() })
    }

    @Test
    fun `Archive is offered only when an archiver is wired, and goes to the receiver`() {
        grant()
        AndroidTriageNotifier(app, canArchive = { true }).notify(alert())
        val n = posted().single()
        assertEquals(listOf("Open", "Draft reply", "Archive"), n.actions.map { it.title.toString() })
        val archive = shadowOf(n.actions[2].actionIntent)
        assertTrue(archive.isBroadcastIntent)
        assertEquals(ComponentName(app, ArchiveReceiver::class.java), archive.savedIntent.component)
        assertEquals(ArchiveReceiver.ACTION, archive.savedIntent.action)
    }

    @Test
    fun `the app's notifier offers Archive only for an account with an Archive folder`() {
        grant()
        val graph = MailGraph(app)
        assertNotNull(graph.notificationArchiver())
        runBlocking {
            graph.database.accounts().upsert(
                Account(
                    account,
                    Address.of("me@example.com"),
                    "me@example.com",
                    "me@example.com",
                    ServerConfig("imap.example.com", 993, ConnectionSecurity.SSL_TLS),
                    ServerConfig("smtp.example.com", 465, ConnectionSecurity.SSL_TLS),
                ).toEntity(createdAtMillis = 1),
            )
        }
        graph.triageNotifier(app).notify(alert(localId = 1))
        assertEquals(listOf("Open", "Draft reply"), posted().single().actions.map { it.title.toString() })

        runBlocking {
            graph.database.folders().upsert(
                listOf(FolderEntity(account.value, "Archive", "Archive", 7, SpecialUse.ARCHIVE, null, null)),
            )
        }
        graph.triageNotifier(app).notify(alert(localId = 2))
        val withArchive =
            posted().first {
                (NotificationLinks.parse(shadowOf(it.contentIntent).savedIntent) as NotificationLink.Open).localId ==
                    2L
            }
        assertEquals(listOf("Open", "Draft reply", "Archive"), withArchive.actions.map { it.title.toString() })
    }
}

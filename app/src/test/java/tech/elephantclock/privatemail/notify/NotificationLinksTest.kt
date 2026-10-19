package tech.elephantclock.privatemail.notify

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.model.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationLinksTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `links survive the round trip`() {
        for (link in listOf(
            NotificationLink.Open(AccountId("a b/c"), 7),
            NotificationLink.DraftReply(AccountId("acc"), Long.MAX_VALUE),
            NotificationLink.Discarded,
        )) {
            assertEquals(link, NotificationLinks.parse(NotificationLinks.intent(app, link)))
        }
    }

    @Test
    fun `other intents are not links`() {
        assertNull(NotificationLinks.parse(null))
        assertNull(NotificationLinks.parse(Intent(Intent.ACTION_MAIN)))
        val open = NotificationLinks.intent(app, NotificationLink.Open(AccountId("a"), 7))
        assertNull(NotificationLinks.parse(Intent(open).setAction(Intent.ACTION_VIEW)))
        assertNull(NotificationLinks.parse(Intent(open).setData(Uri.parse("https://evil.example/open/7?account=a"))))
        assertNull(NotificationLinks.parse(Intent(open).setData(Uri.parse("privatemail://open/-1?account=a"))))
        assertNull(NotificationLinks.parse(Intent(open).setData(Uri.parse("privatemail://open/7"))))
        // An Open action can't be dressed up as a Draft reply.
        assertNull(NotificationLinks.parse(Intent(open).setData(Uri.parse("privatemail://reply/7?account=a"))))
    }

    @Test
    fun `the permission is asked once, from Android 13, and only when it is missing`() {
        assertFalse(NotificationPermission.shouldAsk(app, enabled = false))
        assertFalse(NotificationPermission.shouldAsk(app, enabled = true, sdk = 32))
        assertTrue(NotificationPermission.shouldAsk(app, enabled = true, sdk = 33))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(NotificationPermission.shouldAsk(app, enabled = true, sdk = 33))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationPermission.markAsked(app)
        assertFalse(NotificationPermission.shouldAsk(app, enabled = true, sdk = 33))
    }
}

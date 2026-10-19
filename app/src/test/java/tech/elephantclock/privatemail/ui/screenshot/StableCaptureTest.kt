package tech.elephantclock.privatemail.ui.screenshot

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.feature.inbox.PagedMessageList
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures
import tech.elephantclock.privatemail.feature.inbox.staticPage
import tech.elephantclock.privatemail.feature.inbox.toRow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screenshots of paged lists used to race the first page: the capture could happen on the first
 * frame, before paging (off the main thread) delivered, and record "Loading…". The helper now
 * waits until no paged list shows its loading row. Here the first page is made deliberately late.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class StableCaptureTest {
    private val rows = ReadingFixtures.priority.map { it.toRow(ReadingFixtures.times) }

    @Test
    fun waitsForALateFirstPageBeforeCapturing() {
        // Roborazzi only renders while recording, verifying or comparing (CI always verifies);
        // a plain testDebugUnitTest run never composes the content, so there is nothing to check.
        assumeTrue(listOf("record", "verify", "compare").any { System.getProperty("roborazzi.test.$it") == "true" })
        val shown = AtomicInteger(-1)
        captureScreen("paged_list_late_first_page", ScreenSize.Cover, dark = false) {
            val late =
                remember {
                    flow {
                        Thread.sleep(LATE_MS)
                        emit(staticPage(rows))
                    }.flowOn(Dispatchers.IO)
                }
            val items = late.collectAsLazyPagingItems()
            // Read in composition so this scope recomposes when the page lands.
            val count = items.itemCount
            SideEffect { shown.accumulateAndGet(count, ::maxOf) }
            PagedMessageList(
                items = items,
                selectedId = null,
                accountColors = emptyMap(),
                refreshing = false,
                onRefresh = {},
                onOpen = {},
                emptyText = "Nothing here",
            )
        }
        assertEquals("rows on screen when captured", rows.size, shown.get())
    }

    private companion object {
        const val LATE_MS = 1_500L
    }
}

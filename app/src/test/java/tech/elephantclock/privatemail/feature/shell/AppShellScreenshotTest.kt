package tech.elephantclock.privatemail.feature.shell

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.ui.screenshot.captureAllSizes

/** The shell at the four sizes, light and dark. Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class AppShellScreenshotTest {
    @Test
    fun placeholders() {
        captureAllSizes("shell_placeholder") { info -> AppShell(windowInfo = info) }
    }

    @Test
    fun listAndOpenItem() {
        captureAllSizes("shell_open_item") { info ->
            AppShell(
                windowInfo = info,
                listPane = sampleList,
                detailPane = sampleDetail,
                initialOpenKey = "Prospective client",
            )
        }
    }
}

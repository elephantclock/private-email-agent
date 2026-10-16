package tech.elephantclock.privatemail.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cover-screen width from the mockups (412 dp). Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h960dp-xxhdpi")
class ComponentScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun galleryLight() {
        compose.setContent { ComponentGallery(darkTheme = false) }
        compose.onRoot().captureRoboImage("src/test/screenshots/component_gallery_light.png")
    }

    @Test
    fun galleryDark() {
        compose.setContent { ComponentGallery(darkTheme = true) }
        compose.onRoot().captureRoboImage("src/test/screenshots/component_gallery_dark.png")
    }
}

package tech.elephantclock.privatemail.ui.screenshot

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import tech.elephantclock.privatemail.feature.shell.FoldPosture
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

/**
 * The plan's four screenshot sizes (§ 3). Flex mode is the inner landscape window with a
 * half-opened horizontal hinge across the middle; Robolectric has no FoldingFeature, so the
 * posture is passed in through [WindowInfo].
 */
enum class ScreenSize(
    val tag: String,
    val widthDp: Int,
    val heightDp: Int,
    val posture: FoldPosture = FoldPosture.Flat,
) {
    Cover("cover", 412, 960),
    InnerPortrait("inner_portrait", 750, 832),
    InnerLandscape("inner_landscape", 832, 750),
    Flex("flex", 832, 750, FoldPosture.Tabletop(hingeTopDp = 375f, hingeBottomDp = 375f)),
    ;

    val windowInfo get() = WindowInfo(widthDp, heightDp, posture)
}

/** Records `src/test/screenshots/<name>_<size>_<light|dark>.png`. */
@OptIn(ExperimentalRoborazziApi::class)
fun captureScreen(
    name: String,
    size: ScreenSize,
    dark: Boolean,
    content: @Composable (WindowInfo) -> Unit,
) {
    val theme = if (dark) "dark" else "light"
    captureRoboImage(
        filePath = "src/test/screenshots/${name}_${size.tag}_$theme.png",
        roborazziComposeOptions = RoborazziComposeOptions { size(widthDp = size.widthDp, heightDp = size.heightDp) },
    ) {
        PrivateMailTheme(darkTheme = dark) { content(size.windowInfo) }
    }
}

/** Every size, light and dark: eight images. */
fun captureAllSizes(
    name: String,
    content: @Composable (WindowInfo) -> Unit,
) {
    ScreenSize.entries.forEach { size ->
        listOf(false, true).forEach { dark -> captureScreen(name, size, dark, content) }
    }
}

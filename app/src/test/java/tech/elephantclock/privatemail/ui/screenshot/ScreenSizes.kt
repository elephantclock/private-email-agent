package tech.elephantclock.privatemail.ui.screenshot

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeCaptureOption
import com.github.takahirom.roborazzi.RoborazziComposeComposableOption
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import tech.elephantclock.privatemail.feature.inbox.PAGED_LOADING_TAG
import tech.elephantclock.privatemail.feature.shell.FoldPosture
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme
import java.lang.reflect.Field
import java.time.Duration
import kotlin.coroutines.ContinuationInterceptor

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
        roborazziComposeOptions =
            RoborazziComposeOptions {
                size(widthDp = size.widthDp, heightDp = size.heightDp)
                addOption(AwaitPagedLists())
            },
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

/**
 * Holds the capture until every paged list on screen has its first page. Paging delivers off the
 * main thread, so the first frame can still show "Loading…"; this runs frames until no node
 * tagged [PAGED_LOADING_TAG] is left, and fails rather than record a half-loaded screen.
 */
@OptIn(ExperimentalRoborazziApi::class)
private class AwaitPagedLists :
    RoborazziComposeComposableOption,
    RoborazziComposeCaptureOption {
    private var root: ViewRootForTest? = null

    override fun configureWithComposable(content: @Composable () -> Unit): @Composable () -> Unit =
        {
            root = LocalView.current as? ViewRootForTest
            content()
        }

    override fun beforeCapture() {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        frame(looper)
        while (loading()) {
            check(System.nanoTime() < deadline) { "A paged list was still loading after $TIMEOUT" }
            Thread.sleep(POLL_MS)
            frame(looper)
        }
        // Lay out and draw what just arrived.
        repeat(2) { frame(looper) }
    }

    override fun afterCapture() = Unit

    private fun frame(looper: ShadowLooper) {
        drainComposeDispatcher()
        looper.idleFor(FRAME)
    }

    /**
     * Compose's [AndroidUiDispatcher.Main] is one per process and outlives Robolectric's per-test
     * reset. If a previous test (any compose test) ends while it has a dispatch scheduled, that
     * Handler message is dropped with the old looper but the "scheduled" flag stays set, so the
     * dispatcher queues every later task (effects, paging's collection) and never runs them.
     * That was the real flake: whether "Loading…" got captured depended on the test before.
     * Running its dispatch callback drains the queue and clears the flags; when nothing is stuck
     * it only runs what was due anyway.
     */
    private fun drainComposeDispatcher() {
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
        (DISPATCH_CALLBACK.get(dispatcher) as Runnable).run()
    }

    private fun loading(): Boolean {
        val owner = checkNotNull(root) { "No compose root to inspect" }.semanticsOwner
        return owner.getAllSemanticsNodes(mergingEnabled = false).any {
            it.config.getOrNull(SemanticsProperties.TestTag) == PAGED_LOADING_TAG
        }
    }

    private companion object {
        val FRAME: Duration = Duration.ofMillis(16)
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        const val POLL_MS = 5L
        val DISPATCH_CALLBACK: Field =
            AndroidUiDispatcher::class.java.getDeclaredField("dispatchCallback").apply { isAccessible = true }
    }
}

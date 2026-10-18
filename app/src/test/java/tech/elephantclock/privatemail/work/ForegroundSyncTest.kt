package tech.elephantclock.privatemail.work

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ForegroundSyncTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `IDLE runs only while the app is in the foreground`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        var started = 0
        var stopped = 0
        ForegroundSync(owner, Dispatchers.Main.immediate) {
            started++
            try {
                awaitCancellation()
            } finally {
                stopped++
            }
        }.start()
        val idle = { shadowOf(Looper.getMainLooper()).idle() }

        idle()
        assertEquals(0 to 0, started to stopped)
        owner.registry.currentState = Lifecycle.State.RESUMED
        idle()
        assertEquals(1 to 0, started to stopped)
        owner.registry.currentState = Lifecycle.State.CREATED // app went to the background
        idle()
        assertEquals(1 to 1, started to stopped)
        owner.registry.currentState = Lifecycle.State.STARTED // and came back
        idle()
        assertEquals(2 to 1, started to stopped)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        idle()
        assertEquals(2 to 2, started to stopped)
    }

    @Test
    fun `a failing watch never crashes the app and runs again next time`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        var runs = 0
        ForegroundSync(owner, Dispatchers.Main.immediate) {
            runs++
            throw IllegalStateException("unexpected")
        }.start()
        val idle = { shadowOf(Looper.getMainLooper()).idle() }

        owner.registry.currentState = Lifecycle.State.STARTED
        idle()
        owner.registry.currentState = Lifecycle.State.CREATED
        idle()
        owner.registry.currentState = Lifecycle.State.STARTED
        idle()
        assertEquals(2, runs)
    }
}

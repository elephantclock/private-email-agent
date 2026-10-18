package tech.elephantclock.privatemail.work

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs [watch] (IMAP IDLE, `MailRepository.watchInboxes`) only while the app is visible:
 * started when [owner] (the process lifecycle) reaches STARTED, cancelled when it drops below,
 * which closes the IDLE connections. In the background only [SyncWorker] runs.
 */
class ForegroundSync(
    private val owner: LifecycleOwner,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val watch: suspend () -> Unit,
) {
    fun start(): Job =
        owner.lifecycleScope.launch(dispatcher) {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    watch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing here may crash the app; the next return to the foreground starts over.
                    Log.w(TAG, "foreground sync stopped: ${e.javaClass.simpleName}")
                }
            }
        }

    private companion object {
        const val TAG = "ForegroundSync"
    }
}

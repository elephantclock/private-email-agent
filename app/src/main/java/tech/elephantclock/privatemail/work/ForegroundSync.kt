package tech.elephantclock.privatemail.work

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { watch() }
        }
}

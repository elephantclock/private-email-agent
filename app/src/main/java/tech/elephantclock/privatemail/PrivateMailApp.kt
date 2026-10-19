package tech.elephantclock.privatemail

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.notify.Channels
import tech.elephantclock.privatemail.work.DigestWorker
import tech.elephantclock.privatemail.work.ForegroundSync
import tech.elephantclock.privatemail.work.SyncWorker
import tech.elephantclock.privatemail.work.SyncWorkerFactory

class PrivateMailApp :
    Application(),
    Configuration.Provider {
    val graph: MailGraph by lazy { MailGraph(this) }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(SyncWorkerFactory({ graph.repository }, afterSync = { graph.orchestrator.drain() }))
                .build()

    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
        SyncWorker.schedule(WorkManager.getInstance(this))
        DigestWorker.schedule(WorkManager.getInstance(this))
        // While the app is in the foreground: IDLE on every inbox, and triage reacting to what arrives.
        ForegroundSync(ProcessLifecycleOwner.get()) {
            coroutineScope {
                launch { graph.orchestrator.run() }
                graph.repository.watchInboxes()
            }
        }.start()
    }
}

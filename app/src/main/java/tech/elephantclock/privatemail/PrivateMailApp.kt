package tech.elephantclock.privatemail

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import tech.elephantclock.privatemail.work.ForegroundSync
import tech.elephantclock.privatemail.work.SyncWorker
import tech.elephantclock.privatemail.work.SyncWorkerFactory

class PrivateMailApp :
    Application(),
    Configuration.Provider {
    val graph: MailGraph by lazy { MailGraph(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(SyncWorkerFactory { graph.repository }).build()

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(WorkManager.getInstance(this))
        ForegroundSync(ProcessLifecycleOwner.get()) { graph.repository.watchInboxes() }.start()
    }
}

package tech.elephantclock.privatemail.feature.accounts

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import tech.elephantclock.privatemail.core.data.accounts.AccountRemovalHooks
import tech.elephantclock.privatemail.core.data.accounts.HostPort
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.detail.AttachmentFiles
import tech.elephantclock.privatemail.notify.AndroidTriageNotifier
import tech.elephantclock.privatemail.notify.ArchiveWorker
import tech.elephantclock.privatemail.security.CertificatePinStore
import tech.elephantclock.privatemail.work.WorkManagerFirstRunWork
import java.io.File

/**
 * The app's part of removing an account (T52): its unique first-run work and queued archive
 * taps, its alert notifications (tag `alert:<id>`), pins of servers no other account uses, and
 * attachment files (`cacheDir/attachments/<message id>`). Message bodies are never on disk:
 * they are cached in memory per open message and unreachable once the rows are gone.
 */
class AndroidRemovalHooks(
    context: Context,
    private val workManager: WorkManager,
    private val pins: CertificatePinStore,
    private val cacheDir: File = context.cacheDir,
) : AccountRemovalHooks {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun stopWork(id: AccountId) {
        workManager.cancelUniqueWork(WorkManagerFirstRunWork.uniqueName(id)).await()
        workManager.cancelAllWorkByTag(ArchiveWorker.accountTag(id)).await()
    }

    override fun clearNotifications(id: AccountId) {
        val tag = AndroidTriageNotifier.tag(id)
        notifications.activeNotifications.filter { it.tag == tag }.forEach { notifications.cancel(it.tag, it.id) }
    }

    override fun forgetPins(servers: Set<HostPort>) = servers.forEach { pins.forget(it.host, it.port) }

    override fun deleteFiles(messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        val ids = messageIds.mapTo(HashSet()) { it.toString() }
        AttachmentFiles
            .dirFor(cacheDir, 0)
            .parentFile
            ?.listFiles()
            ?.filter {
                it.name in ids
            }?.forEach { it.deleteRecursively() }
    }
}

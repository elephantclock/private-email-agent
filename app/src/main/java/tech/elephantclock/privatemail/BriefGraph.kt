package tech.elephantclock.privatemail

import tech.elephantclock.privatemail.core.ai.AiAvailability
import tech.elephantclock.privatemail.core.ai.queue.AiQueue
import tech.elephantclock.privatemail.core.data.repo.BriefRepository
import tech.elephantclock.privatemail.core.data.repo.MessageContentRepository
import tech.elephantclock.privatemail.core.data.repo.PasswordImapConnector
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.feature.detail.AiBriefSource
import tech.elephantclock.privatemail.feature.detail.AttachmentFiles
import tech.elephantclock.privatemail.feature.detail.DetailViewModel
import tech.elephantclock.privatemail.feature.detail.RoomDetailSource
import java.io.File
import java.util.WeakHashMap

/*
 * Thread briefs (T32) on top of MailGraph, as extensions so MailGraph stays untouched while other
 * lanes work on it. `ReadingGraph.detailViewModel` uses it with [AiGraph]'s availability and queue.
 */

private val briefRepositories = WeakHashMap<MailGraph, BriefRepository>()

/** One brief cache per process (MailGraph is a process singleton). */
fun MailGraph.briefRepository(): BriefRepository =
    synchronized(briefRepositories) { briefRepositories.getOrPut(this) { BriefRepository(database) } }

/**
 * The detail ViewModel with the thread brief. One [MessageContentRepository] serves both the
 * email's full text and the brief's, so the open message is fetched once when its text is cached.
 */
fun MailGraph.briefDetailViewModel(
    messageId: Long,
    cacheDir: File,
    ai: AiAvailability,
    queue: AiQueue,
): DetailViewModel {
    AttachmentFiles.pruneOld(cacheDir)
    val content = MessageContentRepository(database, PasswordImapConnector(secrets, ImapClient(certificatePins)))
    return DetailViewModel(
        messageId,
        RoomDetailSource(database, content, cacheDir),
        ai,
        briefs = AiBriefSource(briefRepository(), queue, content),
    )
}

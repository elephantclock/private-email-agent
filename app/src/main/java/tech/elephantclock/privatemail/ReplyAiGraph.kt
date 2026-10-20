package tech.elephantclock.privatemail

import tech.elephantclock.privatemail.core.ai.AiAvailability
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.queue.AiQueue
import tech.elephantclock.privatemail.core.data.repo.OutgoingMailRepository
import tech.elephantclock.privatemail.core.data.repo.PasswordImapConnector
import tech.elephantclock.privatemail.core.data.secure.StoredSecret
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.smtp.SmtpSender
import tech.elephantclock.privatemail.feature.reply.QueuedReplyAi
import tech.elephantclock.privatemail.feature.reply.ReplyAi
import tech.elephantclock.privatemail.feature.reply.ReplyViewModel
import tech.elephantclock.privatemail.feature.reply.RoomReplyAiSource
import tech.elephantclock.privatemail.feature.reply.RoomReplySource
import java.util.Locale

/*
 * The reply editor with on-device AI (T33), as MailGraph extensions. ReadingGraph's
 * `replyViewModel` uses them: replyViewModelWithAi(parentId, ai.availability, replyAi(ai.queue)).
 */

/** Every model call, translation included, through [queue] (foreground priority). */
fun MailGraph.replyAi(queue: AiQueue): ReplyAi = QueuedReplyAi(queue)

/** The language the translation preview shows a draft in: the phone's. */
fun deviceLanguage(): LanguageTag =
    Locale
        .getDefault()
        .language
        .takeIf { LanguageTag.isValid(it) }
        ?.let(::LanguageTag) ?: LanguageTag.ENGLISH

/** Like `replyViewModel` in ReadingGraph, plus the AI tools; sending is unchanged. */
fun MailGraph.replyViewModelWithAi(
    parentId: Long,
    availability: AiAvailability,
    replyAi: ReplyAi,
): ReplyViewModel {
    val outgoing =
        OutgoingMailRepository(
            db = database,
            connector = PasswordImapConnector(secrets, ImapClient(certificatePins)),
            passwords = { id -> (secrets.get(id) as? StoredSecret.Found)?.password },
            sender = SmtpSender(certificatePins),
        )
    return ReplyViewModel(
        parentId,
        RoomReplySource(database, outgoing),
        availability,
        replyAi = replyAi,
        aiSource = RoomReplyAiSource(database),
        userLanguage = deviceLanguage(),
    )
}

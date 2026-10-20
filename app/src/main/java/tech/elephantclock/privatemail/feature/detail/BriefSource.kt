package tech.elephantclock.privatemail.feature.detail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.ThreadMessage
import tech.elephantclock.privatemail.core.ai.ThreadSummary
import tech.elephantclock.privatemail.core.ai.queue.AiQueue
import tech.elephantclock.privatemail.core.data.repo.BriefFailure
import tech.elephantclock.privatemail.core.data.repo.BriefOutcome
import tech.elephantclock.privatemail.core.data.repo.BriefRepository
import tech.elephantclock.privatemail.core.data.repo.BriefThread
import tech.elephantclock.privatemail.core.data.repo.ContentResult
import tech.elephantclock.privatemail.core.data.repo.MessageContentRepository
import tech.elephantclock.privatemail.core.data.repo.ThreadBrief
import tech.elephantclock.privatemail.core.model.Address

/** The thread brief for the detail screen (T32). [AiBriefSource] in the app; fakes in tests. */
interface BriefSource {
    /** Emits whenever the open message's thread changes (a message joins or leaves); null = gone. */
    fun changes(messageId: Long): Flow<Any?>

    /** The brief for the thread as it is now: cached, or computed by the on-device model. */
    suspend fun brief(messageId: Long): BriefOutcome
}

/**
 * [BriefRepository] (thread, text, cache) + the model through [queue] (`AiQueue.foreground`, so
 * the call runs only while the user is looking, and the queue's engine is the app's
 * `SafeAiEngine`, which redacts and cleans every text). [fullText] fetches one message's text.
 */
class AiBriefSource(
    private val repository: BriefRepository,
    private val queue: AiQueue,
    private val fullText: suspend (Long) -> String?,
) : BriefSource {
    constructor(repository: BriefRepository, queue: AiQueue, content: MessageContentRepository) :
        this(repository, queue, { id -> (content.body(id) as? ContentResult.Ok)?.value?.text })

    override fun changes(messageId: Long): Flow<Any?> = repository.keys(messageId)

    override suspend fun brief(messageId: Long): BriefOutcome =
        repository.brief(messageId, fullText) { thread ->
            try {
                queue.foreground { engine -> engine.summarize(thread.toAiThread()) }.toOutcome()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A foreground block cancelled from inside (WorkCancelledException) or an engine bug.
                BriefOutcome.Failed(BriefFailure.ERROR)
            }
        }
}

fun BriefThread.toAiThread(): AiThread =
    AiThread(
        subject = subject,
        messages =
            messages.map { m ->
                ThreadMessage(
                    from = m.fromEmail?.let { runCatching { Address.of(it, m.fromName) }.getOrNull() },
                    sentAtMillis = m.sentAtMillis,
                    text = m.text,
                    fromUser = m.fromUser,
                )
            },
    )

fun AiResult<ThreadSummary>.toOutcome(): BriefOutcome =
    when (this) {
        is AiResult.Ok -> {
            BriefOutcome.Ready(ThreadBrief(value.gist, value.status, value.deadline, value.nextSteps))
        }

        is AiResult.Failed -> {
            BriefOutcome.Failed(
                when (failure) {
                    is AiFailure.Unavailable -> BriefFailure.UNAVAILABLE
                    is AiFailure.Timeout -> BriefFailure.TIMEOUT
                    is AiFailure.Refused -> BriefFailure.REFUSED
                    is AiFailure.InvalidOutput -> BriefFailure.INVALID
                    is AiFailure.EngineError -> BriefFailure.ERROR
                },
            )
        }
    }

fun ThreadBrief.toState(): ThreadBriefState.Ready =
    ThreadBriefState.Ready(gist, ThreadStatus.of(status), deadline, nextSteps)

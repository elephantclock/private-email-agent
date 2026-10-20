package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.android.gms.tasks.Task
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.UnavailableReason
import tech.elephantclock.privatemail.core.ai.aiCall
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Shared plumbing for the T33 components: awaiting ML Kit's futures and tasks without extra
 * libraries, GenAI error codes as AiFailure values, and a Prompt API seam.
 */

/** Awaits a Rewriting/Proofreading future; cancelling the coroutine cancels the future. */
internal suspend fun <T> ListenableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (e: CancellationException) {
                cont.cancel(e)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, Runnable::run)
        cont.invokeOnCancellation { cancel(true) }
    }

/** Awaits a Language ID / Translation task (they can't be cancelled; the result is dropped). */
internal suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener(Runnable::run) { task ->
            when {
                task.isCanceled -> {
                    cont.cancel()
                }

                task.isSuccessful -> {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(task.result as T)
                }

                else -> {
                    cont.resumeWithException(task.exception ?: IllegalStateException("task failed"))
                }
            }
        }
    }

/**
 * ML Kit GenAI's `GenAiException.ErrorCode` as a value. Quota and BUSY are worth a retry later
 * (EngineError: the UI offers Retry); background use and missing features are Unavailable.
 */
fun genAiFailure(
    errorCode: Int,
    message: String? = null,
): AiFailure =
    when (errorCode) {
        GenAiException.ErrorCode.BUSY -> AiFailure.EngineError("BUSY: the model is busy, try again")

        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> AiFailure.EngineError("QUOTA: try again later")

        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
        GenAiException.ErrorCode.NOT_AVAILABLE,
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE,
        -> AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_READY))

        GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        -> AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED))

        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> AiFailure.Refused("input too long")

        GenAiException.ErrorCode.REQUEST_TOO_SMALL -> AiFailure.Refused("input too short")

        else -> AiFailure.EngineError("GenAI $errorCode: ${message.orEmpty().take(MAX_DETAIL)}")
    }

/** True when another engine (the Prompt API) may still succeed where this feature failed. */
internal fun AiFailure.worthPromptFallback(): Boolean =
    when (this) {
        is AiFailure.Refused -> reason == "input too long"
        is AiFailure.Unavailable -> true
        else -> false
    }

/** [aiCall] for ML Kit: a [GenAiException] becomes [genAiFailure], anything else EngineError. */
internal suspend fun <T> genAiCall(
    timeoutMillis: Long,
    block: suspend () -> AiResult<T>,
): AiResult<T> =
    aiCall(timeoutMillis) {
        try {
            block()
        } catch (e: GenAiException) {
            AiResult.Failed(genAiFailure(e.errorCode, e.message))
        }
    }

/** The Prompt API as a seam: one text prompt in, the model's text (or null) out. */
fun interface TextGenerator {
    suspend fun generate(
        prompt: String,
        maxOutputTokens: Int,
    ): String?
}

/** Gemini Nano through `Generation.getClient()`. One client, created on first use. */
class PromptTextGenerator(
    private val client: () -> GenerativeModel = { Generation.getClient() },
) : TextGenerator {
    private val model by lazy(client)

    override suspend fun generate(
        prompt: String,
        maxOutputTokens: Int,
    ): String? {
        val request =
            generateContentRequest(TextPart(prompt)) {
                temperature = TEMPERATURE
                this.maxOutputTokens = maxOutputTokens
            }
        return model
            .generateContent(request)
            .candidates
            .firstOrNull()
            ?.text
    }

    private companion object {
        const val TEMPERATURE = 0.3f
    }
}

private const val MAX_DETAIL = 120

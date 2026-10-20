package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.GenAiException
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.UnavailableReason

/**
 * What one `GenAiException` means for the app: the failure to report, how long to leave the
 * model alone ([coolDownMillis], 0 = not at all) and whether the model itself seems gone, so the
 * availability must be checked again ([modelGone]).
 */
data class GenAiError(
    val failure: AiFailure,
    val coolDownMillis: Long = 0,
    val modelGone: Boolean = false,
)

/** Maps ML Kit GenAI error codes onto [AiFailure] (no new failure types: the code goes in the detail). */
object GenAiErrors {
    /** AICore is serving someone else; try again soon. */
    const val BUSY_COOL_DOWN_MILLIS = 5_000L

    /** The per-app battery quota is used up; AICore gives a retry delay, otherwise wait this long. */
    const val QUOTA_COOL_DOWN_MILLIS = 15 * 60_000L

    private val notReady = AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_READY))

    fun of(
        code: Int,
        retryDelayMillis: Long? = null,
    ): GenAiError =
        when (code) {
            // Inference is foreground-only: the caller should not have asked; next foreground works.
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> {
                GenAiError(notReady)
            }

            GenAiException.ErrorCode.BUSY -> {
                GenAiError(notReady, retryDelayMillis ?: BUSY_COOL_DOWN_MILLIS)
            }

            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> {
                GenAiError(notReady, retryDelayMillis ?: QUOTA_COOL_DOWN_MILLIS)
            }

            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            -> {
                GenAiError(notReady, modelGone = true)
            }

            GenAiException.ErrorCode.NOT_SUPPORTED -> {
                GenAiError(
                    AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED)),
                    modelGone = true,
                )
            }

            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> {
                GenAiError(AiFailure.Refused("request too large"))
            }

            GenAiException.ErrorCode.REQUEST_TOO_SMALL -> {
                GenAiError(AiFailure.Refused("request too small"))
            }

            // AICore produced nothing: its safety filter, or the language isn't supported.
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> {
                GenAiError(AiFailure.Refused("no response generated"))
            }

            GenAiException.ErrorCode.CANCELLED -> {
                GenAiError(AiFailure.EngineError("cancelled by AICore"))
            }

            else -> {
                GenAiError(AiFailure.EngineError("GenAI error $code"))
            }
        }

    fun of(e: GenAiException): GenAiError = of(e.errorCode, e.retryDelay?.toMillis()?.takeIf { it > 0 })

    /** The `GenAiException` in [e]'s cause chain (ML Kit sometimes wraps it), or null. */
    fun find(e: Throwable): GenAiException? {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < MAX_DEPTH) {
            if (t is GenAiException) return t
            t = t.cause
        }
        return null
    }

    private const val MAX_DEPTH = 8
}

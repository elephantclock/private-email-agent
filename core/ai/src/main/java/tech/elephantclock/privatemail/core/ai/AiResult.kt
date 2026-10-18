package tech.elephantclock.privatemail.core.ai

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * The outcome of one AI call. Model problems are values, never exceptions: callers fall back to
 * the rules (or hide the feature) on [Failed]. Coroutine cancellation is the one exception that
 * still propagates, so a cancelled caller (or a preempted bulk job) stops promptly.
 */
sealed interface AiResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : AiResult<T>

    data class Failed(
        val failure: AiFailure,
    ) : AiResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value

    val isOk: Boolean get() = this is Ok
}

inline fun <T, R> AiResult<T>.map(transform: (T) -> R): AiResult<R> =
    when (this) {
        is AiResult.Ok -> AiResult.Ok(transform(value))
        is AiResult.Failed -> this
    }

/** Why an AI call produced nothing usable. */
sealed interface AiFailure {
    /** The model took longer than the call's budget. */
    data class Timeout(
        val afterMillis: Long,
    ) : AiFailure

    /** The feature can't run now: rules-only mode for this call. */
    data class Unavailable(
        val status: ModelStatus,
    ) : AiFailure

    /** The model declined (safety filter, unsupported language, input too long). */
    data class Refused(
        val reason: String,
    ) : AiFailure

    /** The model answered, but not in the required shape (bad JSON, unknown folder key …). */
    data class InvalidOutput(
        val detail: String,
    ) : AiFailure

    /** Anything else the engine reported (service error, binder died). */
    data class EngineError(
        val detail: String,
    ) : AiFailure
}

/** Time budgets per call. Classification's P50 target is 1.5 s on the Fold7; the cap is generous. */
data class AiTimeouts(
    val classifyMillis: Long = 5_000,
    val summarizeMillis: Long = 10_000,
    val generateMillis: Long = 15_000,
    val shortTextMillis: Long = 5_000,
)

/**
 * Runs [block] with a timeout and turns engine exceptions into [AiFailure.EngineError]; for
 * implementations (ML Kit, the fake). Cancellation of the caller is rethrown, never swallowed;
 * a cancellation that comes from the engine while the caller is active is an [AiFailure.EngineError].
 */
suspend fun <T> aiCall(
    timeoutMillis: Long,
    block: suspend () -> AiResult<T>,
): AiResult<T> =
    try {
        withTimeout(timeoutMillis) { block() }
    } catch (_: TimeoutCancellationException) {
        AiResult.Failed(AiFailure.Timeout(timeoutMillis))
    } catch (e: CancellationException) {
        // Our caller was cancelled: propagate. Otherwise the engine cancelled its own work
        // (an ML Kit future cancelled by AICore): that's a model problem, so a value.
        currentCoroutineContext().ensureActive()
        AiResult.Failed(AiFailure.EngineError("cancelled by engine: ${e.message ?: ""}"))
    } catch (e: Exception) {
        AiResult.Failed(AiFailure.EngineError(e.javaClass.simpleName + ": " + (e.message ?: "")))
    }

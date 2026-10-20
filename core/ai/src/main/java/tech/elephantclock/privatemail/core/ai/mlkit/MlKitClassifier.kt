package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.prompt.Candidate
import kotlinx.coroutines.CancellationException
import tech.elephantclock.privatemail.core.ai.AiClassification
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.ClassifyItem
import tech.elephantclock.privatemail.core.ai.ClassifyOutcome
import tech.elephantclock.privatemail.core.ai.ClassifyRequest
import tech.elephantclock.privatemail.core.ai.FolderOption
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.UnavailableReason
import tech.elephantclock.privatemail.core.ai.aiCall

/**
 * Classification through the Prompt API (Gemini Nano): [ClassifyPrompt] in, [VerdictParser] out.
 *
 * - **Never throws** for model problems: timeouts, `GenAiException`s ([GenAiErrors]), cut-off or
 *   malformed answers are [AiResult.Failed]. Caller cancellation propagates.
 * - **One call at a time:** items of a batch run one after another, never in parallel; the app's
 *   `AiQueue` (one item per call) serialises everything else.
 * - **Backs off** after BUSY or the battery quota: until the cool-down ends every call fails at
 *   once with `Unavailable`, without touching AICore. "Model gone" codes call [onModelGone], so
 *   the availability is checked again.
 * - **Cold start:** the first call of the process (model load, 7.6 s on the Fold7) gets
 *   [coldTimeoutMillis]; once the model has answered, [AiTimeouts.classifyMillis] applies.
 *
 * Pass already redacted input (`SafeAiEngine` around [MlKitAiEngine]).
 */
class MlKitClassifier(
    private val model: PromptModel,
    private val prompt: ClassifyPrompt = ClassifyPrompt(),
    private val parser: VerdictParser = VerdictParser(),
    private val timeouts: AiTimeouts = AiTimeouts(),
    private val coldTimeoutMillis: Long = COLD_TIMEOUT_MILLIS,
    private val settings: GenerationSettings = SETTINGS,
    /** Monotonic milliseconds, for the cool-down. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onModelGone: () -> Unit = {},
) {
    @Volatile private var warm = false

    @Volatile private var coolDownUntil = Long.MIN_VALUE

    @Volatile private var tokenLimit: Int? = null

    suspend fun classify(request: ClassifyRequest): List<ClassifyOutcome> =
        request.items.map { ClassifyOutcome(it.key, classifyOne(request.folders, it)) }

    suspend fun classifyOne(
        folders: List<FolderOption>,
        item: ClassifyItem,
    ): AiResult<AiClassification> {
        if (clock() < coolDownUntil) return AiResult.Failed(COOLING_DOWN)
        val budget = ClassifyPrompt.charBudget(tokenLimit(), settings.maxOutputTokens)
        val text = prompt.build(folders, item, budget) ?: return AiResult.Failed(AiFailure.Refused("prompt too long"))
        val timeout = if (warm) timeouts.classifyMillis else maxOf(coldTimeoutMillis, timeouts.classifyMillis)
        val result =
            aiCall(timeout) {
                try {
                    val answer = model.generate(text, settings)
                    warm = true
                    if (answer.finishReason == Candidate.FinishReason.MAX_TOKENS) {
                        AiResult.Failed(AiFailure.InvalidOutput("answer cut off at the token limit"))
                    } else {
                        parser.parse(answer.text, folders)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val genAi = GenAiErrors.find(e) ?: throw e
                    AiResult.Failed(handle(GenAiErrors.of(genAi)))
                }
            }
        return result
    }

    private fun handle(error: GenAiError): AiFailure {
        if (error.coolDownMillis > 0) coolDownUntil = clock() + error.coolDownMillis
        if (error.modelGone) {
            tokenLimit = null
            warm = false
            onModelGone()
        }
        return error.failure
    }

    /** The model's token limit, asked once; a failed ask assumes [FALLBACK_TOKEN_LIMIT] this time. */
    private suspend fun tokenLimit(): Int {
        tokenLimit?.let { return it }
        return try {
            model.tokenLimit().takeIf { it > 0 }?.also { tokenLimit = it } ?: FALLBACK_TOKEN_LIMIT
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FALLBACK_TOKEN_LIMIT
        }
    }

    companion object {
        const val COLD_TIMEOUT_MILLIS = 20_000L
        const val FALLBACK_TOKEN_LIMIT = 4_096

        /** Deterministic, and short: the answer is ~60 tokens; more only means a runaway answer. */
        val SETTINGS = GenerationSettings(temperature = 0f, topK = 1, maxOutputTokens = 100)

        private val COOLING_DOWN = AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_READY))
    }
}

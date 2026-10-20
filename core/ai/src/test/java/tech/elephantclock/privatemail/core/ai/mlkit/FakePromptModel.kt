package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A scriptable [PromptModel]: each [generate] takes the next scripted answer (or the last one again). */
class FakePromptModel(
    var status: Int = FeatureStatus.AVAILABLE,
    var limit: Int = 8_192,
) : PromptModel {
    /** Text answers, or Throwables to throw. */
    val answers = ArrayDeque<Any?>()
    var finishReason: Int? = null
    var latencyMillis = 0L
    var warmupError: Throwable? = null
    val prompts = mutableListOf<String>()
    val settingsSeen = mutableListOf<GenerationSettings>()
    var warmups = 0
    var tokenLimitCalls = 0

    fun answer(vararg a: Any?) = apply { answers.addAll(a) }

    override suspend fun checkStatus(): Int = status

    override fun download(): Flow<DownloadStatus> = emptyFlow()

    override suspend fun warmup() {
        warmups++
        warmupError?.let { throw it }
    }

    override suspend fun tokenLimit(): Int {
        tokenLimitCalls++
        return limit
    }

    override suspend fun generate(
        prompt: String,
        settings: GenerationSettings,
    ): Generated {
        prompts += prompt
        settingsSeen += settings
        if (latencyMillis > 0) delay(latencyMillis)
        val next = if (answers.size > 1) answers.removeFirst() else answers.firstOrNull()
        if (next is Throwable) throw next
        return Generated(next as String?, finishReason)
    }
}

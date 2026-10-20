package tech.elephantclock.privatemail.feature.reply

import kotlinx.coroutines.CompletableDeferred
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.DetectedLanguage
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ReplyDraft
import tech.elephantclock.privatemail.core.ai.RewriteStyle
import tech.elephantclock.privatemail.core.ai.TextSuggestion
import tech.elephantclock.privatemail.core.ai.Translation

/**
 * Scriptable [ReplyAi]: language by script (Cyrillic = Russian), answers per call, and an
 * optional [gate] that holds a generate call until the test completes it. Translation knows two
 * Russian ↔ English sentences, line by line; anything else is `[text]`.
 */
class FakeReplyAi(
    override val canGenerate: Boolean = true,
) : ReplyAi {
    private val pairs = mapOf("Здравствуйте, Иван!" to "Hello, Ivan!", "Во вторник удобно." to "Tuesday works.")
    val calls = mutableListOf<String>()
    var draftArgs: Triple<AiThread, LanguageTag, String?>? = null
    var answer: (String) -> AiResult<String> = { AiResult.Ok("AI: $it") }
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun detectLanguage(text: String): AiResult<DetectedLanguage> {
        calls += "detect"
        return AiResult.Ok(
            when {
                text.isBlank() || text.length < 3 -> DetectedLanguage(LanguageTag.UNDETERMINED, 0f)
                text.any { it in 'а'..'я' || it in 'А'..'Я' } -> DetectedLanguage(LanguageTag.RUSSIAN, 0.9f)
                else -> DetectedLanguage(LanguageTag.ENGLISH, 0.9f)
            },
        )
    }

    private suspend fun generate(
        call: String,
        input: String,
    ): AiResult<String> {
        calls += call
        gate?.await()
        return answer(input)
    }

    override suspend fun draft(
        thread: AiThread,
        language: LanguageTag,
        styleSample: String?,
    ): AiResult<ReplyDraft> {
        draftArgs = Triple(thread, language, styleSample)
        return when (val r = generate("draft", thread.messages.last().text)) {
            is AiResult.Ok -> AiResult.Ok(ReplyDraft(r.value, language))
            is AiResult.Failed -> r
        }
    }

    override suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ) = suggestion(generate("rewrite:$style", text))

    override suspend fun proofread(text: String) = suggestion(generate("proofread", text))

    private fun suggestion(r: AiResult<String>): AiResult<TextSuggestion> =
        when (r) {
            is AiResult.Ok -> AiResult.Ok(TextSuggestion(r.value))
            is AiResult.Failed -> r
        }

    override suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Translation> {
        calls += "translate"
        val out =
            text.lines().joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    from == LanguageTag.RUSSIAN -> pairs[line] ?: "[$line]"
                    else -> pairs.entries.firstOrNull { it.value == line }?.key ?: "[$line]"
                }
            }
        return AiResult.Ok(Translation(out, from, to))
    }

    companion object {
        val failure = AiResult.Failed(AiFailure.EngineError("BUSY"))
    }
}

package tech.elephantclock.privatemail.core.ai.mlkit

import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.Translation
import tech.elephantclock.privatemail.core.ai.map

/**
 * `AiEngine.translate` (T33) with Gemini Nano through the Prompt API: one call, the text fenced
 * as data, line breaks, names, numbers and ⟦…⟧ placeholders kept. No translation models to
 * download (ML Kit Translation was dropped: ~17 MB of native code per ABI). Input arrives redacted
 * from `SafeAiEngine`, which restores the placeholders in the result.
 */
class NanoTranslator(
    private val generator: TextGenerator,
    private val prompts: ReplyPrompts = ReplyPrompts(),
    private val timeouts: AiTimeouts = AiTimeouts(),
) {
    suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Translation> {
        if (!translatable(from) || !translatable(to)) return AiResult.Failed(AiFailure.Refused(UNSUPPORTED))
        if (from.primary == to.primary || text.isBlank()) return AiResult.Ok(Translation(text, from, to))
        val result =
            genAiCall(timeouts.generateMillis) {
                ReplyPrompts
                    .clean(generator.generate(prompts.translate(text, from, to), ReplyPrompts.OWN_TEXT_OUTPUT_TOKENS))
                    .map { Translation(it, from, to) }
            }
        // A wrong day in a preview the user trusts is worse than no preview.
        if (result is AiResult.Ok && !Weekdays.kept(text, from, result.value.text, to)) {
            return AiResult.Failed(AiFailure.InvalidOutput(WEEKDAY_CHANGED))
        }
        return result
    }

    companion object {
        const val UNSUPPORTED = "language can't be translated"
        const val WEEKDAY_CHANGED = "translation changed a weekday"
        private const val SCRIPT_SUBTAG = 4

        /** Not undetermined and no script subtag ("ru-Latn" is transliterated, not Russian script). */
        fun translatable(tag: LanguageTag): Boolean =
            tag != LanguageTag.UNDETERMINED &&
                tag.value
                    .split('-')
                    .drop(1)
                    .none { it.length == SCRIPT_SUBTAG && it.all(Char::isLetter) }
    }
}

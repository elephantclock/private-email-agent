package tech.elephantclock.privatemail.core.ai.mlkit

import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ReplyDraft
import tech.elephantclock.privatemail.core.ai.ReplyTone
import tech.elephantclock.privatemail.core.ai.map

/**
 * `AiEngine.draftReply` with the Prompt API (T33): a reply in [language] (the caller detects the
 * sender's language), in the user's style when a [styleSample] from their Sent mail is given.
 * Input arrives redacted from `SafeAiEngine`, which also restores IBANs in the result. Nothing
 * here can send: the draft only goes into the editor.
 */
class MlKitReplyDrafter(
    private val generator: TextGenerator = PromptTextGenerator(),
    private val prompts: ReplyPrompts = ReplyPrompts(),
    private val timeouts: AiTimeouts = AiTimeouts(),
) {
    suspend fun draftReply(
        thread: AiThread,
        tone: ReplyTone,
        language: LanguageTag,
        styleSample: String? = null,
    ): AiResult<ReplyDraft> =
        genAiCall(timeouts.generateMillis) {
            val prompt = prompts.draft(thread, tone, language, styleSample)
            ReplyPrompts
                .clean(generator.generate(prompt, ReplyPrompts.DRAFT_OUTPUT_TOKENS))
                .map { ReplyDraft(it, language) }
        }
}

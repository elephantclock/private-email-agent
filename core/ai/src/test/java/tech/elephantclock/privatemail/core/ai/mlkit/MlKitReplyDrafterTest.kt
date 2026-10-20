package tech.elephantclock.privatemail.core.ai.mlkit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiEngine
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.ClassifyOutcome
import tech.elephantclock.privatemail.core.ai.ClassifyRequest
import tech.elephantclock.privatemail.core.ai.DetectedLanguage
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ReplyDraft
import tech.elephantclock.privatemail.core.ai.ReplyTone
import tech.elephantclock.privatemail.core.ai.RewriteStyle
import tech.elephantclock.privatemail.core.ai.TextSuggestion
import tech.elephantclock.privatemail.core.ai.ThreadMessage
import tech.elephantclock.privatemail.core.ai.ThreadSummary
import tech.elephantclock.privatemail.core.ai.Translation
import tech.elephantclock.privatemail.core.ai.safety.SafeAiEngine
import tech.elephantclock.privatemail.core.model.Address

/** The drafter behind `SafeAiEngine`, the way `MlKitAiEngine` will run it. */
class MlKitReplyDrafterTest {
    private val prompts = mutableListOf<String>()
    private var answer = "Здравствуйте, Иван! Во вторник удобно.\nSam"
    private val drafter =
        MlKitReplyDrafter({ p, _ ->
            prompts += p
            answer
        })

    /** Only draftReply is under test; the rest isn't reached. */
    private val engine =
        object : AiEngine {
            override suspend fun classify(request: ClassifyRequest): List<ClassifyOutcome> = error("unused")

            override suspend fun summarize(thread: AiThread): AiResult<ThreadSummary> = error("unused")

            override suspend fun draftReply(
                thread: AiThread,
                tone: ReplyTone,
                language: LanguageTag,
                styleSample: String?,
            ) = drafter.draftReply(thread, tone, language, styleSample)

            override suspend fun rewrite(
                text: String,
                style: RewriteStyle,
            ): AiResult<TextSuggestion> = error("unused")

            override suspend fun proofread(text: String): AiResult<TextSuggestion> = error("unused")

            override suspend fun detectLanguage(text: String): AiResult<DetectedLanguage> = error("unused")

            override suspend fun translate(
                text: String,
                from: LanguageTag,
                to: LanguageTag,
            ): AiResult<Translation> = error("unused")
        }
    private val safe = SafeAiEngine(engine)

    private val thread =
        AiThread(
            "Встреча",
            listOf(
                ThreadMessage(
                    Address.of("ivan@example.ru", "Иван"),
                    1L,
                    "Ваш код: 4829. Можем встретиться во вторник? IBAN DE89 3704 0044 0532 0130 00",
                ),
            ),
        )

    @Test
    fun `the model sees no secret from the thread or the style sample, and drafts in the requested language`() =
        runTest {
            val r =
                safe.draftReply(
                    thread,
                    ReplyTone.NEUTRAL,
                    LanguageTag.RUSSIAN,
                    "Hi Ann, code 112233 is your login code\nSam",
                )
            assertEquals(AiResult.Ok(ReplyDraft("Здравствуйте, Иван! Во вторник удобно.\nSam", LanguageTag.RUSSIAN)), r)
            val prompt = prompts.single()
            assertFalse(prompt.contains("4829"))
            assertFalse(prompt.contains("112233"))
            assertFalse(prompt.contains("DE89"))
            assertTrue(prompt.contains("⟦CODE_1⟧"))
            assertTrue(prompt.contains("Write the reply in Russian."))
        }

    @Test
    fun `an IBAN placeholder comes back in the draft, a one-time code never does`() =
        runTest {
            answer = "Оплатите на ⟦IBAN_1⟧, код ⟦CODE_1⟧"
            val text = (safe.draftReply(thread, ReplyTone.NEUTRAL, LanguageTag.RUSSIAN) as AiResult.Ok).value.text
            assertTrue(text.contains("DE89 3704 0044 0532 0130 00"))
            assertTrue(text.contains("⟦CODE_1⟧"))
            assertFalse(text.contains("4829"))
        }

    @Test
    fun `an empty answer is a failure, not an empty draft`() =
        runTest {
            answer = "  "
            val r = safe.draftReply(thread, ReplyTone.NEUTRAL, LanguageTag.RUSSIAN) as AiResult.Failed
            assertTrue(r.failure is AiFailure.InvalidOutput)
        }
}

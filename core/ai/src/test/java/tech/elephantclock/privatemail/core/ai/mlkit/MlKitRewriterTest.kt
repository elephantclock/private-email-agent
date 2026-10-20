package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.rewriting.RewriterOptions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.RewriteStyle
import tech.elephantclock.privatemail.core.ai.TextSuggestion
import tech.elephantclock.privatemail.core.ai.UnavailableReason

class MlKitRewriterTest {
    private val rewriteCalls = mutableListOf<Triple<String, Int, Int>>()
    private val proofreadCalls = mutableListOf<Pair<String, Int>>()
    private val prompts = mutableListOf<String>()
    private var api: () -> List<String> = { listOf("Short.", "Brief.") }
    private val generator =
        TextGenerator { p, _ ->
            prompts += p
            "Prompt answer"
        }
    private var language = LanguageTag.ENGLISH

    private val rewriter =
        MlKitRewriter(
            backend = { t, type, lang ->
                rewriteCalls += Triple(t, type, lang)
                api()
            },
            fallback = generator,
            languageOf = { language },
        )
    private val proofreader =
        MlKitProofreader(
            backend = { t, lang ->
                proofreadCalls += t to lang
                api()
            },
            fallback = generator,
            languageOf = { language },
        )

    @Test
    fun `short English text goes to the Rewriting API with the style's output type`() =
        runTest {
            val r = rewriter.rewrite("Thanks a lot for your message.", RewriteStyle.SHORTER)
            assertEquals(AiResult.Ok(TextSuggestion("Short.", listOf("Brief."))), r)
            assertEquals(RewriterOptions.OutputType.SHORTEN, rewriteCalls.single().second)
            assertEquals(RewriterOptions.Language.ENGLISH, rewriteCalls.single().third)
            assertTrue(prompts.isEmpty())
            assertEquals(RewriterOptions.OutputType.PROFESSIONAL, MlKitRewriter.outputType(RewriteStyle.MORE_FORMAL))
            assertEquals(RewriterOptions.OutputType.FRIENDLY, MlKitRewriter.outputType(RewriteStyle.FRIENDLIER))
        }

    @Test
    fun `Russian goes to the Prompt API, which the Rewriting API doesn't serve`() =
        runTest {
            language = LanguageTag.RUSSIAN
            val r = rewriter.rewrite("Спасибо за письмо", RewriteStyle.MORE_FORMAL)
            assertEquals(AiResult.Ok(TextSuggestion("Prompt answer")), r)
            assertTrue(rewriteCalls.isEmpty())
            assertTrue(prompts.single().contains("Write in Russian."))
            assertTrue(prompts.single().contains("more formal"))
        }

    @Test
    fun `features that aren't downloaded are skipped, not called`() =
        runTest {
            // On the Fold7 a call to a missing feature hangs until the timeout instead of failing.
            val notReadyRewriter =
                MlKitRewriter(
                    backend =
                        object : RewriteBackend {
                            override suspend fun rewrite(
                                text: String,
                                outputType: Int,
                                language: Int,
                            ): List<String> = error("must not be called")

                            override suspend fun ready(
                                outputType: Int,
                                language: Int,
                            ) = false
                        },
                    fallback = generator,
                    languageOf = { language },
                )
            val notReadyProofreader =
                MlKitProofreader(
                    backend =
                        object : ProofreadBackend {
                            override suspend fun proofread(
                                text: String,
                                language: Int,
                            ): List<String> = error("must not be called")

                            override suspend fun ready(language: Int) = false
                        },
                    fallback = generator,
                    languageOf = { language },
                )
            assertEquals(
                AiResult.Ok(TextSuggestion("Prompt answer")),
                notReadyRewriter.rewrite("Thanks a lot for your message.", RewriteStyle.SHORTER),
            )
            assertEquals(AiResult.Ok(TextSuggestion("Prompt answer")), notReadyProofreader.proofread("Thnks a lot."))
            assertEquals(2, prompts.size)
        }

    @Test
    fun `long text and missing features fall back to the Prompt API`() =
        runTest {
            rewriter.rewrite("a".repeat(MlKitRewriter.SHORT_INPUT_CHARS + 1), RewriteStyle.FRIENDLIER)
            assertTrue(rewriteCalls.isEmpty())
            assertEquals(1, prompts.size)

            api = { throw GenAiException("not yet", null, GenAiException.ErrorCode.NOT_AVAILABLE) }
            assertEquals(
                AiResult.Ok(TextSuggestion("Prompt answer")),
                rewriter.rewrite("Hi there", RewriteStyle.SHORTER),
            )
            api = { throw GenAiException("big", null, GenAiException.ErrorCode.REQUEST_TOO_LARGE) }
            assertEquals(
                AiResult.Ok(TextSuggestion("Prompt answer")),
                rewriter.rewrite("Hi there", RewriteStyle.SHORTER),
            )
            assertEquals(3, prompts.size)
        }

    @Test
    fun `busy, quota and background errors are failures, not a second model call`() =
        runTest {
            api = { throw GenAiException("busy", null, GenAiException.ErrorCode.BUSY) }
            val busy = rewriter.rewrite("Hi there", RewriteStyle.SHORTER) as AiResult.Failed
            assertTrue(busy.failure is AiFailure.EngineError)
            assertTrue(prompts.isEmpty())
            api = { emptyList() }
            assertTrue(
                (rewriter.rewrite("Hi", RewriteStyle.SHORTER) as AiResult.Failed).failure is AiFailure.InvalidOutput,
            )
        }

    @Test
    fun `GenAI error codes map onto failures`() {
        assertEquals(
            AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_READY)),
            genAiFailure(GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED),
        )
        assertEquals(
            AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED)),
            genAiFailure(GenAiException.ErrorCode.NOT_SUPPORTED),
        )
        assertEquals(AiFailure.Refused("input too long"), genAiFailure(GenAiException.ErrorCode.REQUEST_TOO_LARGE))
        assertTrue(genAiFailure(GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED) is AiFailure.EngineError)
        assertTrue(genAiFailure(999, "x") is AiFailure.EngineError)
    }

    @Test
    fun `proofreading uses its API for English and the Prompt API for Russian`() =
        runTest {
            assertEquals("Short.", (proofreader.proofread("Thnks for yor mesage") as AiResult.Ok).value.text)
            assertEquals(RewriterOptions.Language.ENGLISH, proofreadCalls.single().second)
            language = LanguageTag.RUSSIAN
            assertEquals("Prompt answer", (proofreader.proofread("Спосибо") as AiResult.Ok).value.text)
            assertTrue(prompts.single().contains("Correct spelling"))
            assertEquals(1, proofreadCalls.size)
        }

    @Test
    fun `only the seven Rewriting languages have a code`() {
        assertEquals(RewriterOptions.Language.GERMAN, genAiTextLanguage(LanguageTag("de-AT")))
        assertEquals(null, genAiTextLanguage(LanguageTag.RUSSIAN))
        assertEquals(null, genAiTextLanguage(LanguageTag.UNDETERMINED))
    }
}

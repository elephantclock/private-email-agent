package tech.elephantclock.privatemail.core.ai

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitLanguageId
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitProofreadBackend
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitProofreader
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitReplyDrafter
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitRewriteBackend
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitRewriter
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitTranslator
import tech.elephantclock.privatemail.core.ai.mlkit.PromptTextGenerator
import tech.elephantclock.privatemail.core.ai.mlkit.TranslationReadiness
import tech.elephantclock.privatemail.core.ai.mlkit.awaitResult
import tech.elephantclock.privatemail.core.model.Address

/**
 * T33 on the device (tag PEA-AI): Language ID, Russian ↔ English translation, English rewrite and
 * proofread (their own APIs), a Russian rewrite (Prompt API fallback) and a Russian reply draft.
 * Synthetic text only. Logs results and latency; model problems are logged, not failed.
 * Opt-in: `-Pandroid.testInstrumentationRunnerArguments.peaReply=1`. The Russian translation model
 * (~30 MB) is downloaded only with `peaReplyDownload=1`, and only on Wi-Fi (waits up to 5 min).
 */
@RunWith(AndroidJUnit4::class)
class DeviceReplyAiTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun replyTools() {
        assumeTrue(args.getString("peaReply") == "1")
        ActivityScenario.launch(ForegroundActivity::class.java).use {
            runBlocking {
                val languageId = MlKitLanguageId()
                val languageOf: suspend (String) -> LanguageTag = {
                    languageId.detectLanguage(it).getOrNull()?.language ?: LanguageTag.UNDETERMINED
                }
                timed("language ID (ru)") { languageId.detectLanguage(RUSSIAN) }
                timed("language ID (en)") { languageId.detectLanguage(ENGLISH) }

                val translator = MlKitTranslator()
                val ru = LanguageTag.RUSSIAN
                val en = LanguageTag.ENGLISH
                var ready = translator.readiness(ru, en)
                Log.i(TAG, "translation readiness ru->en: $ready")
                if (ready is TranslationReadiness.NeedsDownload && args.getString("peaReplyDownload") == "1") {
                    Log.i(TAG, "downloading ${ready.languages} on Wi-Fi only …")
                    val r = withTimeoutOrNull(DOWNLOAD_WAIT_MS) { translator.download(ru, en) }
                    Log.i(TAG, "download: ${r ?: "still waiting for Wi-Fi after ${DOWNLOAD_WAIT_MS / 1000} s"}")
                    ready = translator.readiness(ru, en)
                }
                if (ready == TranslationReadiness.Ready) {
                    timed("translate ru->en") { translator.translate(RUSSIAN, ru, en) }
                    timed("translate en->ru") { translator.translate(ENGLISH, en, ru) }
                } else {
                    Log.i(TAG, "translation needs Wi-Fi to download the model(s) once: $ready")
                }

                val generator = PromptTextGenerator()
                engineCheck()
                MlKitRewriteBackend(context).use { backend ->
                    val rewriter = MlKitRewriter(backend, generator, languageOf)
                    timed("rewrite en SHORTER") { rewriter.rewrite(ENGLISH, RewriteStyle.SHORTER) }
                    timed("rewrite en MORE_FORMAL") { rewriter.rewrite(ENGLISH, RewriteStyle.MORE_FORMAL) }
                    timed("rewrite ru FRIENDLIER (prompt)") { rewriter.rewrite(RUSSIAN, RewriteStyle.FRIENDLIER) }
                }
                MlKitProofreadBackend(context).use { backend ->
                    val proofreader = MlKitProofreader(backend, generator, languageOf)
                    timed("proofread en") { proofreader.proofread("Thnks for yor mesage, tusday works for me.") }
                    timed("proofread ru (prompt)") { proofreader.proofread("Спасибо за письмо, вторнк мне подходит.") }
                }
                val thread =
                    AiThread(
                        "Встреча",
                        listOf(
                            ThreadMessage(
                                Address.of("ivan@example.ru", "Иван"),
                                null,
                                "Здравствуйте! Можем встретиться во вторник в 15:00?",
                            ),
                        ),
                    )
                timed("draft ru") {
                    MlKitReplyDrafter(
                        generator,
                    ).draftReply(thread, ReplyTone.NEUTRAL, ru, "Hi Ann,\nThanks, works for me.\nSam")
                }
            }
        }
    }

    /**
     * Which engine answers: the Rewriting/Proofreading feature status and one direct call each,
     * so a Prompt API fallback in [MlKitRewriter]/[MlKitProofreader] can't hide a failure (e.g. the
     * genai-common beta1/beta4 mix, or a feature AICore hasn't downloaded).
     */
    private suspend fun engineCheck() {
        val rewriterClient =
            Rewriting.getClient(
                RewriterOptions
                    .builder(context)
                    .setLanguage(RewriterOptions.Language.ENGLISH)
                    .setOutputType(RewriterOptions.OutputType.SHORTEN)
                    .build(),
            )
        val proofreaderClient =
            Proofreading.getClient(
                ProofreaderOptions
                    .builder(context)
                    .setLanguage(ProofreaderOptions.Language.ENGLISH)
                    .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                    .build(),
            )
        try {
            Log.i(TAG, "rewriting status: ${probe { rewriterClient.checkFeatureStatus().awaitResult() }}")
            Log.i(TAG, "proofreading status: ${probe { proofreaderClient.checkFeatureStatus().awaitResult() }}")
        } finally {
            rewriterClient.close()
            proofreaderClient.close()
        }
        MlKitRewriteBackend(context).use { b ->
            val r = probe { b.rewrite(ENGLISH, RewriterOptions.OutputType.SHORTEN, RewriterOptions.Language.ENGLISH) }
            Log.i(TAG, "Rewriting API direct: $r")
        }
        MlKitProofreadBackend(context).use { b ->
            Log.i(
                TAG,
                "Proofreading API direct: ${probe {
                    b.proofread(
                        "Thnks for yor mesage.",
                        ProofreaderOptions.Language.ENGLISH,
                    )
                }}",
            )
        }
    }

    private suspend fun probe(block: suspend () -> Any?): String =
        try {
            block().toString()
        } catch (e: GenAiException) {
            "GenAiException ${e.errorCode}: ${e.message}"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }

    private suspend fun timed(
        label: String,
        block: suspend () -> AiResult<*>,
    ) {
        val start = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        Log.i(TAG, "$label in $ms ms: ${r.toString().replace('\n', ' ').take(300)}")
    }

    private companion object {
        const val TAG = "PEA-AI"
        const val DOWNLOAD_WAIT_MS = 300_000L
        const val RUSSIAN = "Здравствуйте, Иван! Спасибо за письмо. Вторник мне подходит."
        const val ENGLISH = "Hi Ivan, thanks a lot for your message. Tuesday at 3 pm works well for me."
    }
}

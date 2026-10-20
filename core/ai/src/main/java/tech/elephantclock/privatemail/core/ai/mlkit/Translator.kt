package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslatorOptions
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.Translation
import tech.elephantclock.privatemail.core.ai.aiCall
import kotlin.coroutines.cancellation.CancellationException
import com.google.mlkit.nl.translate.Translation as MlKitTranslation

/** Whether a language pair can be translated on this device right now. */
sealed interface TranslationReadiness {
    data object Ready : TranslationReadiness

    /** These languages' models (about 30 MB each) must be downloaded once, on Wi-Fi. */
    data class NeedsDownload(
        val languages: List<LanguageTag>,
    ) : TranslationReadiness

    /** On-device translation doesn't cover one of the languages. */
    data object Unsupported : TranslationReadiness
}

/** The model management the reply editor needs; outside `AiQueue` (downloads aren't inference). */
interface TranslationModels {
    suspend fun readiness(
        from: LanguageTag,
        to: LanguageTag,
    ): TranslationReadiness

    /** Downloads the missing models **only on Wi-Fi**; suspends until then. */
    suspend fun download(
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Unit>
}

/** ML Kit Translation as a seam. Codes are ML Kit's ("ru", "en", "zh" …). */
interface TranslationBackend {
    /** ML Kit's code for a primary language subtag, or null when it can't translate it. */
    fun code(primary: String): String?

    suspend fun isDownloaded(code: String): Boolean

    /** Must only download on Wi-Fi. */
    suspend fun downloadOnWifi(code: String)

    /** Translates each of [lines] with one client (loading a model is the slow part). */
    suspend fun translate(
        lines: List<String>,
        fromCode: String,
        toCode: String,
    ): List<String>
}

class MlKitTranslationBackend(
    private val models: () -> RemoteModelManager = { RemoteModelManager.getInstance() },
) : TranslationBackend {
    override fun code(primary: String): String? = TranslateLanguage.fromLanguageTag(primary)

    override suspend fun isDownloaded(code: String): Boolean =
        models().isModelDownloaded(TranslateRemoteModel.Builder(code).build()).awaitResult() == true

    override suspend fun downloadOnWifi(code: String) {
        val wifi = DownloadConditions.Builder().requireWifi().build()
        models().download(TranslateRemoteModel.Builder(code).build(), wifi).awaitResult()
    }

    override suspend fun translate(
        lines: List<String>,
        fromCode: String,
        toCode: String,
    ): List<String> {
        val options =
            TranslatorOptions
                .Builder()
                .setSourceLanguage(fromCode)
                .setTargetLanguage(toCode)
                .build()
        // A translator for missing models would download them on any network: callers check first.
        val translator = MlKitTranslation.getClient(options)
        return try {
            lines.map { translator.translate(it).awaitResult() }
        } finally {
            translator.close()
        }
    }
}

/**
 * `AiEngine.translate` plus [TranslationModels] (T33), on ML Kit's on-device Translation (no
 * Gemini Nano). [translate] never downloads: with a model missing it fails at once with
 * `Unavailable(Downloadable)`, and the UI offers [download], which waits for Wi-Fi. Text is
 * translated line by line so the draft's line breaks survive.
 */
class MlKitTranslator(
    private val backend: TranslationBackend = MlKitTranslationBackend(),
    private val timeouts: AiTimeouts = AiTimeouts(),
) : TranslationModels {
    override suspend fun readiness(
        from: LanguageTag,
        to: LanguageTag,
    ): TranslationReadiness {
        val codes = codes(from, to) ?: return TranslationReadiness.Unsupported
        val missing =
            listOf(from to codes.first, to to codes.second)
                .distinctBy { it.second }
                .filter { (_, code) -> !isDownloaded(code) }
                .map { it.first }
        return if (missing.isEmpty()) TranslationReadiness.Ready else TranslationReadiness.NeedsDownload(missing)
    }

    override suspend fun download(
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Unit> {
        val codes = codes(from, to) ?: return AiResult.Failed(AiFailure.Refused(UNSUPPORTED))
        return try {
            listOf(codes.first, codes.second).distinct().filter { !isDownloaded(it) }.forEach {
                backend.downloadOnWifi(it)
            }
            AiResult.Ok(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiResult.Failed(AiFailure.EngineError("download failed: ${e.javaClass.simpleName}"))
        }
    }

    suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Translation> =
        aiCall(timeouts.generateMillis) {
            val codes = codes(from, to) ?: return@aiCall AiResult.Failed(AiFailure.Refused(UNSUPPORTED))
            if (codes.first == codes.second) return@aiCall AiResult.Ok(Translation(text, from, to))
            if (readiness(from, to) != TranslationReadiness.Ready) {
                return@aiCall AiResult.Failed(AiFailure.Unavailable(ModelStatus.Downloadable()))
            }
            val lines = text.lines()
            val filled = lines.filter { it.isNotBlank() }
            val translated = backend.translate(filled, codes.first, codes.second)
            if (translated.size != filled.size) {
                return@aiCall AiResult.Failed(AiFailure.InvalidOutput("${translated.size} of ${filled.size} lines"))
            }
            val done = translated.iterator()
            val out = lines.map { if (it.isBlank()) it else done.next() }
            AiResult.Ok(Translation(out.joinToString("\n"), from, to))
        }

    /** ML Kit codes for both tags; null if either is undetermined, has a script subtag or isn't covered. */
    internal fun codes(
        from: LanguageTag,
        to: LanguageTag,
    ): Pair<String, String>? {
        val a = codeOf(from) ?: return null
        val b = codeOf(to) ?: return null
        return a to b
    }

    private fun codeOf(tag: LanguageTag): String? {
        if (tag == LanguageTag.UNDETERMINED) return null
        // "ru-Latn" (transliterated) or "zh-Latn" (pinyin) can't go through the script's model.
        if (tag.value
                .split('-')
                .drop(1)
                .any { it.length == SCRIPT_SUBTAG && it.all(Char::isLetter) }
        ) {
            return null
        }
        return backend.code(tag.primary)
    }

    private suspend fun isDownloaded(code: String) =
        try {
            backend.isDownloaded(code)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private companion object {
        const val UNSUPPORTED = "language not supported by on-device translation"
        const val SCRIPT_SUBTAG = 4
    }
}

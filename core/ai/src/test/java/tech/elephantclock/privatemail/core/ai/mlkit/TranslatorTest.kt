package tech.elephantclock.privatemail.core.ai.mlkit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.DetectedLanguage
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.Translation

/** A dictionary "model" for Russian and English; downloads are recorded. */
class FakeTranslationBackend(
    downloaded: Set<String> = setOf("en"),
) : TranslationBackend {
    val downloaded = downloaded.toMutableSet()
    val downloads = mutableListOf<String>()
    val translated = mutableListOf<String>()
    var batches = 0
    private val ruEn = mapOf("Привет, Анна!" to "Hello, Anna!", "Во вторник удобно." to "Tuesday works.")

    override fun code(primary: String) = primary.takeIf { it in setOf("en", "ru", "de") }

    override suspend fun isDownloaded(code: String) = code in downloaded

    override suspend fun downloadOnWifi(code: String) {
        downloads += code
        downloaded += code
    }

    override suspend fun translate(
        lines: List<String>,
        fromCode: String,
        toCode: String,
    ): List<String> {
        batches++
        return lines.map { translateLine(it, fromCode, toCode) }
    }

    private fun translateLine(
        text: String,
        fromCode: String,
        toCode: String,
    ): String {
        check(fromCode in downloaded && toCode in downloaded) { "would download on any network" }
        translated += text
        return when (fromCode to toCode) {
            "ru" to "en" -> ruEn[text] ?: "[$text]"
            "en" to "ru" -> ruEn.entries.firstOrNull { it.value == text }?.key ?: "[$text]"
            else -> "[$text]"
        }
    }
}

class TranslatorTest {
    private val backend = FakeTranslationBackend()
    private val translator = MlKitTranslator(backend)
    private val ru = LanguageTag.RUSSIAN
    private val en = LanguageTag.ENGLISH

    @Test
    fun `a missing Russian model is reported, never downloaded by translate`() =
        runTest {
            assertEquals(TranslationReadiness.NeedsDownload(listOf(ru)), translator.readiness(ru, en))
            val r = translator.translate("Привет, Анна!", ru, en)
            assertEquals(AiResult.Failed(AiFailure.Unavailable(ModelStatus.Downloadable())), r)
            assertTrue(backend.downloads.isEmpty())
            assertTrue(backend.translated.isEmpty())
        }

    @Test
    fun `after the Wi-Fi download Russian and English translate both ways, keeping line breaks`() =
        runTest {
            assertEquals(AiResult.Ok(Unit), translator.download(ru, en))
            assertEquals(listOf("ru"), backend.downloads)
            assertEquals(TranslationReadiness.Ready, translator.readiness(ru, en))
            val text = "Привет, Анна!\n\nВо вторник удобно."
            assertEquals(
                AiResult.Ok(Translation("Hello, Anna!\n\nTuesday works.", ru, en)),
                translator.translate(text, ru, en),
            )
            assertEquals(1, backend.batches) // one client for the whole text
            assertEquals(
                AiResult.Ok(Translation("Привет, Анна!\n\nВо вторник удобно.", en, ru)),
                translator.translate("Hello, Anna!\n\nTuesday works.", en, ru),
            )
            assertEquals(AiResult.Ok(Unit), translator.download(ru, en))
            assertEquals(listOf("ru"), backend.downloads) // nothing downloaded twice
        }

    @Test
    fun `undetermined, transliterated and uncovered languages are unsupported`() =
        runTest {
            assertEquals(TranslationReadiness.Unsupported, translator.readiness(LanguageTag.UNDETERMINED, en))
            assertEquals(TranslationReadiness.Unsupported, translator.readiness(LanguageTag("ru-Latn"), en))
            assertEquals(TranslationReadiness.Unsupported, translator.readiness(LanguageTag("xx"), en))
            assertTrue(
                (translator.translate("x", LanguageTag("xx"), en) as AiResult.Failed).failure is AiFailure.Refused,
            )
            assertTrue((translator.download(LanguageTag("xx"), en) as AiResult.Failed).failure is AiFailure.Refused)
        }

    @Test
    fun `same language is returned as is, regional tags use the primary language`() =
        runTest {
            assertEquals(
                AiResult.Ok(Translation("Hi", LanguageTag("en-GB"), en)),
                translator.translate("Hi", LanguageTag("en-GB"), en),
            )
            assertTrue(backend.translated.isEmpty())
        }

    @Test
    fun `language ID picks the most likely language and gives up below the threshold`() =
        runTest {
            var answer = listOf("en" to 0.2f, "ru" to 0.78f)
            val id = MlKitLanguageId({ answer })
            assertEquals(AiResult.Ok(DetectedLanguage(ru, 0.78f)), id.detectLanguage("Во вторник удобно."))
            answer = listOf("ru" to 0.3f)
            assertEquals(AiResult.Ok(MlKitLanguageId.UNDETERMINED), id.detectLanguage("ok"))
            answer = listOf("not a tag!" to 0.9f)
            assertEquals(AiResult.Ok(MlKitLanguageId.UNDETERMINED), id.detectLanguage("ok"))
            assertEquals(AiResult.Ok(MlKitLanguageId.UNDETERMINED), id.detectLanguage("   "))
        }
}

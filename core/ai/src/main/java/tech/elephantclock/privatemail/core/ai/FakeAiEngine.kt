package tech.elephantclock.privatemail.core.ai

import kotlinx.coroutines.delay
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.Importance

/**
 * A deterministic [AiEngine] for tests and previews. Behaviour is scriptable per call; the
 * defaults are simple, stable transforms. Honours [availability] (an unavailable feature fails
 * with [AiFailure.Unavailable]) and [timeouts] (a [latencyMillis] above the budget fails with
 * [AiFailure.Timeout]); [latencyMillis] uses `delay`, so `runTest` virtual time drives it.
 */
class FakeAiEngine(
    val availability: AiAvailability = FakeAiAvailability(),
    private val timeouts: AiTimeouts = AiTimeouts(),
) : AiEngine {
    /** Simulated model time per call. */
    var latencyMillis: Long = 0

    /** Default classifier: Priority (or the first folder offered), NORMAL, low confidence. */
    var classifier: (ClassifyItem, ClassifyRequest) -> AiResult<AiClassification> = { item, request ->
        val folder = request.folders.firstOrNull { it.target.role == FolderRole.PRIORITY } ?: request.folders.first()
        AiResult.Ok(AiClassification(folder.target, Importance.NORMAL, 0.5f, "fake: ${item.subject.take(40)}"))
    }

    var summarizer: (AiThread) -> AiResult<ThreadSummary> = { thread ->
        AiResult.Ok(ThreadSummary(gist = firstSentence(thread.messages.last().text)))
    }

    var drafter: (AiThread, ReplyTone, LanguageTag) -> AiResult<ReplyDraft> = { thread, tone, language ->
        AiResult.Ok(ReplyDraft("[$tone/$language] Re: ${thread.subject}", language))
    }

    var rewriter: (String, RewriteStyle) -> AiResult<TextSuggestion> = { text, style ->
        AiResult.Ok(TextSuggestion(if (style == RewriteStyle.SHORTER) firstSentence(text) else "[$style] $text"))
    }

    var proofreader: (String) -> AiResult<TextSuggestion> = { text ->
        AiResult.Ok(
            TextSuggestion(text.oneLine(text.length)),
        )
    }

    var languageDetector: (String) -> AiResult<DetectedLanguage> = { text -> AiResult.Ok(guessLanguage(text)) }

    var translator: (String, LanguageTag, LanguageTag) -> AiResult<Translation> = { text, from, to ->
        AiResult.Ok(Translation("[$to] $text", from, to))
    }

    private val scripted = mutableMapOf<String, AiResult<AiClassification>>()

    /** Fixes the result for one message key, over [classifier]. */
    fun script(
        key: String,
        result: AiResult<AiClassification>,
    ) {
        scripted[key] = result
    }

    private val callLog = mutableListOf<AiCall>()
    private val classifyLog = mutableListOf<String>()
    private val requestLog = mutableListOf<ClassifyRequest>()
    private var running = 0

    /** Every call in order, including ones that failed as unavailable. */
    val calls: List<AiCall> get() = callLog.toList()

    /** Keys of the items passed to [classify], in order. */
    val classifiedKeys: List<String> get() = classifyLog.toList()

    /** The exact requests the engine saw (after any wrapper's redaction). */
    val classifyRequests: List<ClassifyRequest> get() = requestLog.toList()

    /** Highest number of calls that were running at once (the queue keeps this at 1). */
    var maxConcurrent: Int = 0
        private set

    override suspend fun classify(request: ClassifyRequest): List<ClassifyOutcome> {
        requestLog += request
        classifyLog += request.items.map { it.key }
        val batch =
            run(AiCall.CLASSIFY, timeouts.classifyMillis) {
                AiResult.Ok(request.items.map { ClassifyOutcome(it.key, scripted[it.key] ?: classifier(it, request)) })
            }
        return when (batch) {
            is AiResult.Ok -> batch.value
            is AiResult.Failed -> request.items.map { ClassifyOutcome(it.key, batch) }
        }
    }

    override suspend fun summarize(thread: AiThread) =
        run(AiCall.SUMMARIZE, timeouts.summarizeMillis) {
            summarizer(thread)
        }

    override suspend fun draftReply(
        thread: AiThread,
        tone: ReplyTone,
        language: LanguageTag,
        styleSample: String?,
    ) = run(AiCall.DRAFT_REPLY, timeouts.generateMillis) { drafter(thread, tone, language) }

    override suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ) = run(AiCall.REWRITE, timeouts.generateMillis) { rewriter(text, style) }

    override suspend fun proofread(text: String) = run(AiCall.PROOFREAD, timeouts.generateMillis) { proofreader(text) }

    override suspend fun detectLanguage(text: String) =
        run(AiCall.DETECT_LANGUAGE, timeouts.shortTextMillis) { languageDetector(text) }

    override suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ) = run(AiCall.TRANSLATE, timeouts.generateMillis) { translator(text, from, to) }

    private suspend fun <T> run(
        call: AiCall,
        timeoutMillis: Long,
        block: () -> AiResult<T>,
    ): AiResult<T> {
        callLog += call
        val status = availability.status(call.feature()).value
        if (!status.canRun) return AiResult.Failed(AiFailure.Unavailable(status))
        running++
        maxConcurrent = maxOf(maxConcurrent, running)
        try {
            return aiCall(timeoutMillis) {
                if (latencyMillis > 0) delay(latencyMillis)
                block()
            }
        } finally {
            running--
        }
    }

    companion object {
        fun firstSentence(text: String): String {
            val t = text.trim()
            val end = t.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
            return (if (end >= 0) t.substring(0, end + 1) else t).trim().take(AiLimits.SUMMARY_CHARS)
        }

        /** Cyrillic-majority text is Russian, Latin-majority English, otherwise undetermined. */
        fun guessLanguage(text: String): DetectedLanguage {
            var cyrillic = 0
            var latin = 0
            for (c in text.take(AiLimits.TEXT_CHARS)) {
                if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.CYRILLIC) cyrillic++
                if (c in 'a'..'z' || c in 'A'..'Z') latin++
            }
            val total = cyrillic + latin
            return when {
                total == 0 -> DetectedLanguage(LanguageTag.UNDETERMINED, 0f)
                cyrillic > latin -> DetectedLanguage(LanguageTag.RUSSIAN, cyrillic.toFloat() / total)
                else -> DetectedLanguage(LanguageTag.ENGLISH, latin.toFloat() / total)
            }
        }
    }
}

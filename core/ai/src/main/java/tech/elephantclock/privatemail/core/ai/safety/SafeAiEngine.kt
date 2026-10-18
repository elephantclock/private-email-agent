package tech.elephantclock.privatemail.core.ai.safety

import tech.elephantclock.privatemail.core.ai.AiEngine
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiLimits
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
import tech.elephantclock.privatemail.core.ai.ThreadSummary
import tech.elephantclock.privatemail.core.ai.Translation
import tech.elephantclock.privatemail.core.ai.map

/**
 * The [AiEngine] the app uses: wraps the real one so that
 * - every text is [InjectionGuard.clean]ed and [Redactor]-masked before the model sees it;
 * - classification answers must name an offered folder, one per item, and messages that try to
 *   steer the model can't claim URGENT/HIGH ([InjectionGuard.limit]);
 * - placeholders are restored only where the text goes back to the user: rewrite, proofread and
 *   translate get everything back (it's their own text or mail), reply drafts only IBANs, and
 *   summaries and classification reasons stay masked (they're stored and shown in lists).
 */
class SafeAiEngine(
    private val delegate: AiEngine,
    private val redactor: Redactor = Redactor(),
    private val guard: InjectionGuard = InjectionGuard(),
) : AiEngine {
    override suspend fun classify(request: ClassifyRequest): List<ClassifyOutcome> {
        val reports = request.items.associate { it.key to guard.scan(it.subject, it.preview, it.from?.name.orEmpty()) }
        val safeItems =
            request.items.map { item ->
                val session = redactor.session()
                item.copy(
                    from =
                        item.from?.let {
                            it.copy(
                                name =
                                    it.name?.let { n ->
                                        session.redact(guard.clean(n)).take(AiLimits.NAME_CHARS)
                                    },
                            )
                        },
                    subject = session.redact(guard.clean(item.subject)).take(AiLimits.SUBJECT_CHARS),
                    preview = session.redact(guard.clean(item.preview)).take(AiLimits.PREVIEW_CHARS),
                )
            }
        val answers = delegate.classify(request.copy(items = safeItems)).groupBy { it.key }
        val offered = request.folders.map { it.target }.toSet()
        return request.items.map { item ->
            val answer = answers[item.key]
            val result =
                when {
                    answer == null -> {
                        AiResult.Failed(AiFailure.InvalidOutput("no answer for this message"))
                    }

                    answer.size > 1 -> {
                        AiResult.Failed(AiFailure.InvalidOutput("several answers for one message"))
                    }

                    else -> {
                        when (val r = answer.single().result) {
                            is AiResult.Failed -> {
                                r
                            }

                            is AiResult.Ok -> {
                                if (r.value.target in offered) {
                                    AiResult.Ok(guard.limit(r.value, reports.getValue(item.key)))
                                } else {
                                    AiResult.Failed(AiFailure.InvalidOutput("folder not offered: ${r.value.target}"))
                                }
                            }
                        }
                    }
                }
            ClassifyOutcome(item.key, result)
        }
    }

    override suspend fun summarize(thread: AiThread): AiResult<ThreadSummary> =
        delegate.summarize(safeThread(thread, redactor.session()))

    override suspend fun draftReply(
        thread: AiThread,
        tone: ReplyTone,
        language: LanguageTag,
        styleSample: String?,
    ): AiResult<ReplyDraft> {
        val session = redactor.session()
        val safe = safeThread(thread, session)
        val sample = styleSample?.let { session.redact(guard.clean(it)) }
        return delegate.draftReply(safe, tone, language, sample).map {
            it.copy(text = session.restore(it.text, Redactor.REPLY_RESTORABLE))
        }
    }

    override suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ): AiResult<TextSuggestion> = ownText(text) { delegate.rewrite(it, style) }

    override suspend fun proofread(text: String): AiResult<TextSuggestion> = ownText(text) { delegate.proofread(it) }

    override suspend fun detectLanguage(text: String): AiResult<DetectedLanguage> =
        delegate.detectLanguage(redactor.redact(guard.clean(text)))

    override suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Translation> {
        val session = redactor.session()
        return delegate
            .translate(
                session.redact(guard.clean(text)),
                from,
                to,
            ).map { it.copy(text = session.restore(it.text)) }
    }

    private suspend fun ownText(
        text: String,
        call: suspend (String) -> AiResult<TextSuggestion>,
    ): AiResult<TextSuggestion> {
        val session = redactor.session()
        return call(session.redact(guard.clean(text))).map { s ->
            TextSuggestion(session.restore(s.text), s.alternatives.map { session.restore(it) })
        }
    }

    private fun safeThread(
        thread: AiThread,
        session: RedactionSession,
    ) = AiThread(
        subject = session.redact(guard.clean(thread.subject)).take(AiLimits.SUBJECT_CHARS),
        messages = thread.messages.map { it.copy(text = session.redact(guard.clean(it.text))) },
    )
}

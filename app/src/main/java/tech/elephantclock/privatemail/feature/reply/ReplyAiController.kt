package tech.elephantclock.privatemail.feature.reply

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.map
import tech.elephantclock.privatemail.core.ai.mlkit.TranslationReadiness

/**
 * The reply editor's AI tools (T33): draft, rewrite, proofread, one-step undo and the
 * translation preview. It only ever changes [ReplyUiState.body], and only when
 * - the body is still exactly what the action started from (typing never blocks, and a late
 *   result never overwrites what the user typed meanwhile), and
 * - no send is pending or running (the confirmation shows the text that will go out).
 */
internal class ReplyAiController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ReplyUiState>,
    private val ai: ReplyAi,
    private val material: suspend () -> ReplyMaterial?,
) {
    private var translation: Job? = null
    private val user: LanguageTag get() = state.value.ai.userLanguage

    fun run(action: AiAction) {
        val s = state.value
        if (s.ai.working != null || !s.ai.available(action) || !s.aiMayEdit) return
        if (action != AiAction.DRAFT && s.body.isBlank()) return
        val start = s.body
        state.update { it.copy(ai = it.ai.copy(working = action, failed = null, skipped = false)) }
        scope.launch {
            val result =
                try {
                    compute(action, start)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AiResult.Failed(AiFailure.EngineError(e.javaClass.simpleName))
                }
            var applied = false
            state.update { cur ->
                val idle = cur.ai.copy(working = null)
                when (result) {
                    is AiResult.Failed -> {
                        cur.copy(
                            ai =
                                idle.copy(
                                    failed = action,
                                    failedUnavailable = result.failure is AiFailure.Unavailable,
                                ),
                        )
                    }

                    is AiResult.Ok -> {
                        if (cur.body == start && cur.aiMayEdit) {
                            applied = true
                            cur.copy(body = result.value, ai = idle.copy(undo = start, lastChange = action))
                        } else {
                            cur.copy(ai = idle.copy(skipped = true))
                        }
                    }
                }
            }
            if (applied && state.value.ai.translation !is TranslationUi.Idle) translate()
        }
    }

    /** Runs the failed action again. */
    fun retry() {
        val failed = state.value.ai.failed ?: return
        run(failed)
    }

    /** Puts the text from before the last AI change back, and refreshes a shown preview. */
    fun undo() {
        var undone = false
        state.update {
            val previous = it.ai.undo
            if (previous == null || !it.aiMayEdit || it.ai.working != null) {
                it
            } else {
                undone = true
                it.copy(body = previous, ai = it.ai.copy(undo = null, lastChange = null))
            }
        }
        if (undone && state.value.ai.translation !is TranslationUi.Idle) translate()
    }

    /** The user typed: the AI change is now theirs (no undo), and the preview is out of date. */
    fun onUserEdit() =
        state.update {
            val t = it.ai.translation
            it.copy(
                ai =
                    it.ai.copy(
                        undo = null,
                        lastChange = null,
                        failed = null,
                        skipped = false,
                        translation = if (t is TranslationUi.Ready) t.copy(stale = true) else t,
                    ),
            )
        }

    /** Shows what the draft says in the user's language. Never downloads anything by itself. */
    fun translate() {
        if (!state.value.ai.translateAvailable) return
        val text = state.value.body
        if (text.isBlank()) return
        translation?.cancel()
        setTranslation(TranslationUi.Working)
        translation =
            scope.launch {
                setTranslation(
                    try {
                        preview(text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        TranslationUi.Failed
                    },
                )
            }
    }

    /** The user agreed to download the missing model(s); ML Kit waits for Wi-Fi. */
    fun downloadTranslation() {
        val needed = state.value.ai.translation as? TranslationUi.NeedsDownload ?: return
        translation?.cancel()
        setTranslation(TranslationUi.Downloading(needed.from, needed.languages))
        translation =
            scope.launch {
                when (ai.downloadTranslation(needed.from, user)) {
                    is AiResult.Ok -> translate()
                    is AiResult.Failed -> setTranslation(TranslationUi.Failed)
                }
            }
    }

    private suspend fun compute(
        action: AiAction,
        start: String,
    ): AiResult<String> =
        when (action) {
            AiAction.DRAFT -> draft()
            AiAction.PROOFREAD -> ai.proofread(start).map { it.text }
            else -> ai.rewrite(start, requireNotNull(action.style)).map { it.text }
        }

    /** In the sender's language (Language ID on their message), else the user's own. */
    private suspend fun draft(): AiResult<String> {
        val m = material() ?: return AiResult.Failed(AiFailure.EngineError("message not found"))
        val language =
            ai
                .detectLanguage(
                    m.thread.messages
                        .last()
                        .text,
                ).getOrNull()
                ?.language
                ?.takeIf { it != LanguageTag.UNDETERMINED } ?: user
        return ai.draft(m.thread, language, m.styleSample).map { it.text }
    }

    private suspend fun preview(text: String): TranslationUi {
        val from =
            ai
                .detectLanguage(text)
                .getOrNull()
                ?.language
                ?.takeIf { it != LanguageTag.UNDETERMINED }
                ?: return TranslationUi.Undetermined
        if (from.primary == user.primary) return TranslationUi.SameLanguage
        return when (val ready = ai.translationReadiness(from, user)) {
            TranslationReadiness.Unsupported -> {
                TranslationUi.Unsupported(from)
            }

            is TranslationReadiness.NeedsDownload -> {
                TranslationUi.NeedsDownload(from, ready.languages)
            }

            TranslationReadiness.Ready -> {
                when (val r = ai.translate(text, from, user)) {
                    is AiResult.Ok -> TranslationUi.Ready(r.value.text, from, stale = state.value.body != text)
                    is AiResult.Failed -> TranslationUi.Failed
                }
            }
        }
    }

    private fun setTranslation(t: TranslationUi) = state.update { it.copy(ai = it.ai.copy(translation = t)) }
}

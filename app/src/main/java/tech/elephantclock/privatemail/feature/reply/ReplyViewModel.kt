package tech.elephantclock.privatemail.feature.reply

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.core.ai.AiAvailability
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.data.repo.DraftOutcome
import tech.elephantclock.privatemail.core.data.repo.SendOutcome
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.ReplyParent
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.StoredMessage
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.Address

data class ReplyUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val from: String = "",
    val to: String = "",
    val subject: String = "",
    val body: String = "",
    /** Draft, rewrite, proofread, undo and the translation preview (T33). */
    val ai: ReplyAiUi = ReplyAiUi(),
    val savingDraft: Boolean = false,
    val draft: StoredMessage? = null,
    /** The message the confirmation dialog shows; null = no dialog. Sending needs it. */
    val pending: OutgoingMessage? = null,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val notice: ReplyNotice? = null,
) {
    val busy: Boolean get() = sending || savingDraft

    /** Rewrite chips need the on-device model; shown disabled without it. */
    val rewriteAvailable: Boolean get() = ai.rewriteAvailable

    val translateAvailable: Boolean get() = ai.translateAvailable

    /** AI may change the body only while the user could: not behind the send confirmation. */
    val aiMayEdit: Boolean get() = !loading && !missing && pending == null && !sending && !sent
}

/**
 * The reply editor (T46). Nothing leaves the device without the user: [requestSend] only opens
 * the confirmation; [send] needs the [SendApproval] that the confirmed tap handler creates for
 * exactly [ReplyUiState.pending], and it is `@UserInitiatedSend` itself, so nothing else can call
 * it without opting in. Every send attempt needs a fresh confirmation.
 *
 * AI (T33, [replyAi]) only edits the text in the editor, through [ReplyAiController]; it has no
 * path to [send]. [userLanguage] is what the translation preview translates the draft into.
 */
class ReplyViewModel(
    private val parentId: Long,
    private val source: ReplySource,
    ai: AiAvailability,
    replyAi: ReplyAi = NoReplyAi,
    aiSource: ReplyAiSource = NoReplyAiSource,
    userLanguage: LanguageTag = LanguageTag.ENGLISH,
) : ViewModel() {
    private val local =
        MutableStateFlow(
            ReplyUiState(ai = ReplyAiUi(translateAvailable = replyAi.canTranslate, userLanguage = userLanguage)),
        )
    val state: StateFlow<ReplyUiState> = local.asStateFlow()

    private val aiTools = ReplyAiController(viewModelScope, local, replyAi) { aiSource.material(parentId) }

    private var account: Account? = null
    private var parent: ReplyParent? = null

    /** One Message-ID for the whole session: every draft save and the send share it. */
    private var messageId: String? = null

    init {
        if (replyAi.canGenerate) {
            viewModelScope.launch {
                combine(
                    ai.status(AiFeature.PROMPT),
                    ai.status(AiFeature.REWRITE),
                    ai.status(AiFeature.PROOFREAD),
                ) { prompt, rewrite, proofread ->
                    Triple(prompt.canRun, rewrite.canRun || prompt.canRun, proofread.canRun || prompt.canRun)
                }.collect { (draft, rewrite, proofread) ->
                    local.update {
                        it.copy(
                            ai =
                                it.ai.copy(
                                    draftAvailable = draft,
                                    rewriteAvailable = rewrite,
                                    proofreadAvailable = proofread,
                                ),
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            val ctx = source.context(parentId)
            if (ctx == null) {
                local.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            account = ctx.account
            parent = ReplyParent.of(ctx.parent)
            messageId = OutgoingMessage.newMessageId(ctx.account.email.domain)
            val replyTo = ctx.parent.replyTo.ifEmpty { listOfNotNull(ctx.parent.from) }
            local.update {
                it.copy(
                    loading = false,
                    from = ctx.account.email.email,
                    to = replyTo.joinToString(", ") { a -> a.email },
                    subject = replySubject(ctx.parent.subject),
                )
            }
        }
    }

    fun setTo(text: String) = edit { it.copy(to = text) }

    fun setSubject(text: String) = edit { it.copy(subject = text.replace(Regex("[\\r\\n]+"), " ")) }

    fun setBody(text: String) {
        val before = local.value
        edit { it.copy(body = text) }
        if (local.value.body != before.body) aiTools.onUserEdit()
    }

    /** Draft a reply, or rewrite/proofread the current text. */
    fun runAi(action: AiAction) = aiTools.run(action)

    fun retryAi() = aiTools.retry()

    fun undoAi() = aiTools.undo()

    fun translatePreview() = aiTools.translate()

    fun downloadTranslation() = aiTools.downloadTranslation()

    private fun edit(change: (ReplyUiState) -> ReplyUiState) =
        local.update { if (it.sending || it.sent) it else change(it).copy(notice = null) }

    /** Validates what is on screen and opens the confirmation. Sends nothing. */
    fun requestSend() {
        if (local.value.busy || local.value.sent) return
        when (val built = build()) {
            is Built.Invalid -> local.update { it.copy(notice = built.notice) }
            is Built.Ok -> local.update { it.copy(pending = built.message, notice = null) }
        }
    }

    fun cancelSend() = local.update { it.copy(pending = null) }

    /**
     * Sends [message] with [approval]; only from the confirmed Send handler. Ignored unless
     * [message] is the one the dialog showed and no send is running (a double tap sends once).
     */
    @UserInitiatedSend
    fun send(
        message: OutgoingMessage,
        approval: SendApproval,
    ) {
        val s = local.value
        val accountId = account?.id ?: return
        if (s.pending != message || s.sending || s.sent) return
        local.update { it.copy(pending = null, sending = true, notice = null) }
        viewModelScope.launch {
            val outcome =
                try {
                    source.send(accountId, message, approval, s.draft)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            val notice = ReplyNotice.of(outcome)
            local.update {
                it.copy(
                    sending = false,
                    sent = notice.sent,
                    notice = notice,
                    draft = if (notice.sent) null else it.draft,
                )
            }
        }
    }

    fun saveDraft() {
        if (local.value.busy || local.value.sent) return
        val accountId = account?.id ?: return
        val message =
            when (val built = build(requireRecipient = false)) {
                is Built.Invalid -> return local.update { it.copy(notice = built.notice) }
                is Built.Ok -> built.message
            }
        local.update { it.copy(savingDraft = true, notice = null) }
        viewModelScope.launch {
            val outcome =
                try {
                    source.saveDraft(accountId, message, local.value.draft)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    DraftOutcome.Failed(null)
                }
            local.update {
                when (outcome) {
                    is DraftOutcome.Saved -> {
                        it.copy(
                            savingDraft = false,
                            draft = outcome.stored,
                            notice = ReplyNotice.DraftSaved,
                        )
                    }

                    DraftOutcome.NeedsSignIn -> {
                        it.copy(savingDraft = false, notice = ReplyNotice.NeedsSignIn)
                    }

                    is DraftOutcome.Failed -> {
                        it.copy(savingDraft = false, notice = ReplyNotice.DraftFailed)
                    }
                }
            }
        }
    }

    private sealed interface Built {
        data class Ok(
            val message: OutgoingMessage,
        ) : Built

        data class Invalid(
            val notice: ReplyNotice,
        ) : Built
    }

    private fun build(requireRecipient: Boolean = true): Built {
        val s = local.value
        val acc = account ?: return Built.Invalid(ReplyNotice.NotReady)
        val id = messageId ?: return Built.Invalid(ReplyNotice.NotReady)
        val parts =
            s.to
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (parts.any { !looksLikeAddress(it) }) return Built.Invalid(ReplyNotice.BadAddress)
        if (requireRecipient && parts.isEmpty()) return Built.Invalid(ReplyNotice.NoRecipient)
        return try {
            Built.Ok(
                OutgoingMessage(
                    from = acc.email,
                    subject = s.subject.replace(Regex("[\\r\\n]+"), " ").trim(),
                    text = s.body,
                    to = parts.map { Address.of(it) },
                    parent = parent,
                    messageId = id,
                ),
            )
        } catch (_: IllegalArgumentException) {
            Built.Invalid(ReplyNotice.BadAddress)
        }
    }

    companion object {
        private val address = Regex("^[^\\s@<>,;\"]+@[^\\s@<>,;\"]+\\.[^\\s@<>,;\"]+$")

        fun looksLikeAddress(text: String): Boolean = address.matches(text) && text.none { it.isISOControl() }

        /** "Re: " once, whatever the parent's prefix case; one line. */
        fun replySubject(subject: String): String {
            val oneLine = subject.replace(Regex("[\\r\\n]+"), " ").trim()
            return if (oneLine.startsWith("re:", ignoreCase = true)) oneLine else "Re: $oneLine"
        }
    }
}

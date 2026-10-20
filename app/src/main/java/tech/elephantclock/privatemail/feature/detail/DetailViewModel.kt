package tech.elephantclock.privatemail.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tech.elephantclock.privatemail.core.ai.AiAvailability
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.data.db.AccountEntity
import tech.elephantclock.privatemail.core.data.db.MessageWithVerdict
import tech.elephantclock.privatemail.core.data.db.decodeAddresses
import tech.elephantclock.privatemail.core.data.db.toBody
import tech.elephantclock.privatemail.core.data.repo.BriefOutcome
import tech.elephantclock.privatemail.core.data.repo.ContentResult
import tech.elephantclock.privatemail.core.mail.auth.needsUserAction
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.feature.inbox.TimeLabels
import tech.elephantclock.privatemail.feature.inbox.toLevel
import java.io.File

/**
 * One open email (T45). Headers, triage brief and preview come from the database at once; the
 * full text is fetched when the screen opens (read-only, `BODY.PEEK`), attachments only when
 * tapped. Opening a message never marks it read: the plan keeps the app read-only on the server
 * except for triage moves and the user's explicit actions.
 *
 * The thread brief (T32) comes from [briefs] while the screen collects [watchBrief]; null keeps
 * the rules-only text. It is asked for only while the email is on screen (GenAI is foreground-only,
 * and ViewModels of emails the user has left stay alive), and gives up after [briefTimeoutMillis].
 */
class DetailViewModel(
    private val messageId: Long,
    private val source: DetailSource,
    ai: AiAvailability,
    private val times: TimeLabels = TimeLabels(),
    private val briefs: BriefSource? = null,
    private val briefTimeoutMillis: Long = BRIEF_TIMEOUT_MILLIS,
) : ViewModel() {
    private val local = MutableStateFlow(DetailUiState())

    /** Parts this ViewModel downloaded completely; any other file on disk may be a cut-off leftover. */
    private val finished = mutableSetOf<String>()
    val state: StateFlow<DetailUiState> = local.asStateFlow()

    /** The gist can come from the Summarization or the Prompt API, so either one is enough. */
    private val aiOn: Flow<Boolean> =
        combine(ai.status(AiFeature.SUMMARIZE), ai.status(AiFeature.PROMPT)) { s, p -> s.canRun || p.canRun }
            .distinctUntilChanged()

    init {
        viewModelScope.launch {
            combine(source.message(messageId), source.accounts(), aiOn) { m, accounts, on ->
                Triple(m, accounts, on)
            }.collect { (message, accounts, on) -> show(message, accounts, on) }
        }
        viewModelScope.launch { loadBody() }
    }

    /**
     * Keeps the thread brief current while the caller collects (the screen, in a LaunchedEffect):
     * asks again when the thread changes or the model becomes available; cancelling stops it.
     */
    suspend fun watchBrief() {
        val briefs = briefs ?: return
        combine(aiOn, briefs.changes(messageId)) { on, key -> on to key }
            .distinctUntilChanged()
            .collectLatest { (on, key) ->
                if (!on || key == null) {
                    local.update { it.copy(thread = ThreadBriefState.Off) }
                    return@collectLatest
                }
                local.update { it.copy(thread = ThreadBriefState.Loading) }
                val outcome = withTimeoutOrNull(briefTimeoutMillis) { briefs.brief(messageId) }
                val next =
                    when (outcome) {
                        is BriefOutcome.Ready -> outcome.brief.toState()
                        is BriefOutcome.Failed, null -> ThreadBriefState.Fallback
                    }
                local.update { it.copy(thread = next) }
            }
    }

    private fun show(
        item: MessageWithVerdict?,
        accounts: List<AccountEntity>,
        aiOn: Boolean,
    ) {
        if (item == null) {
            local.update { it.copy(loading = false, missing = true) }
            return
        }
        val m = item.message
        val v = item.verdict
        val attachments = m.toBody().attachments.filter { !it.isInline }
        val summary = v?.summary?.takeIf { it.isNotBlank() }
        local.update { s ->
            s.copy(
                loading = false,
                missing = false,
                header =
                    DetailHeader(
                        subject = m.subject,
                        fromName = m.fromName?.takeIf { it.isNotBlank() },
                        fromEmail = m.fromEmail.orEmpty(),
                        to = decodeAddresses(m.toJson).label(),
                        cc = decodeAddresses(m.ccJson).takeIf { it.isNotEmpty() }?.label(),
                        date = times.long(m.receivedAtMillis),
                        account = accounts.takeIf { it.size > 1 }?.firstOrNull { it.id == m.accountId }?.email,
                    ),
                triage =
                    v?.let {
                        TriageBrief(
                            level = it.importance.toLevel(),
                            action = it.action,
                            folder = it.targetRole,
                            customFolder = it.targetCustomName,
                            reason = it.reason,
                            fromAi = it.source == VerdictSource.AI,
                        )
                    },
                brief = summary ?: briefFrom(m.snippet),
                briefFromAi = summary != null,
                aiAvailable = aiOn,
                attachments =
                    attachments.map { a ->
                        s.attachments.firstOrNull { it.partId == a.partId }
                            ?: AttachmentItem(a.partId, AttachmentFiles.safeName(a.fileName), a.mimeType, a.sizeBytes)
                    },
            )
        }
    }

    private suspend fun loadBody() {
        local.update { it.copy(body = BodyState.Loading) }
        val body =
            when (val r = source.body(messageId)) {
                is ContentResult.Ok -> BodyState.Loaded(r.value.text, r.value.fromHtml, r.value.truncated)
                else -> BodyState.Failed(r.problem())
            }
        local.update { it.copy(body = body) }
    }

    fun retryBody() {
        viewModelScope.launch { loadBody() }
    }

    fun toggleOriginal() = local.update { it.copy(originalExpanded = !it.originalExpanded) }

    /** Downloads the attachment on tap (never before) into the cache, then asks the UI to open it. */
    fun openAttachment(partId: String) {
        val item = local.value.attachments.firstOrNull { it.partId == partId } ?: return
        if (item.downloading) return
        if (AttachmentFiles.isRefused(item.mimeType)) {
            updateAttachment(partId) { it.copy(problem = Problem.REFUSED_TYPE) }
            return
        }
        updateAttachment(partId) { it.copy(downloading = true, problem = null) }
        viewModelScope.launch {
            val dir = File(source.attachmentDir(messageId), partId).apply { mkdirs() }
            val target = File(dir, item.name)
            val reuse = partId in finished && target.isFile
            val result = if (reuse) ContentResult.Ok(target.length()) else source.attachment(messageId, partId, target)
            when (result) {
                is ContentResult.Ok -> {
                    finished += partId
                    updateAttachment(partId) { it.copy(downloading = false) }
                    local.update { it.copy(open = OpenRequest(target, item.mimeType)) }
                }

                else -> {
                    target.delete()
                    updateAttachment(partId) { it.copy(downloading = false, problem = result.problem()) }
                }
            }
        }
    }

    /** The UI handled [OpenRequest]; [launched] false = no app on the phone opens this type. */
    fun opened(launched: Boolean) {
        val request = local.value.open ?: return
        local.update { s ->
            s.copy(
                open = null,
                attachments =
                    s.attachments.map {
                        if (!launched &&
                            request.file.parentFile?.name == it.partId
                        ) {
                            it.copy(problem = Problem.NO_APP)
                        } else {
                            it
                        }
                    },
            )
        }
    }

    private fun updateAttachment(
        partId: String,
        change: (AttachmentItem) -> AttachmentItem,
    ) = local.update { s -> s.copy(attachments = s.attachments.map { if (it.partId == partId) change(it) else it }) }

    private fun List<Address>.label() = joinToString(", ") { a -> a.name?.takeIf { it.isNotBlank() } ?: a.email }
}

/**
 * Above the model's own budgets (Summarization 10 s + Prompt 15 s, `AiTimeouts`) plus fetching the
 * newest texts and a short wait in the AI queue; after it the rules-only text stays.
 */
const val BRIEF_TIMEOUT_MILLIS = 35_000L

internal fun ContentResult<*>.problem(): Problem =
    when (this) {
        is ContentResult.Ok -> Problem.NOT_FOUND
        ContentResult.Gone -> Problem.GONE
        ContentResult.NeedsSignIn -> Problem.NEEDS_SIGN_IN
        is ContentResult.TooLarge -> Problem.TOO_LARGE
        ContentResult.NoSuchPart -> Problem.NOT_FOUND
        is ContentResult.Failed -> if (error?.needsUserAction == true) Problem.NEEDS_SIGN_IN else Problem.OFFLINE
    }

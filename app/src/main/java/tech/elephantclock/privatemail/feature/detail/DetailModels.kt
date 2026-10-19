package tech.elephantclock.privatemail.feature.detail

import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.ui.components.ImportanceLevel
import java.io.File

/** Header lines of the open message, from the database. */
data class DetailHeader(
    val subject: String,
    val fromName: String?,
    val fromEmail: String,
    val to: String,
    val cc: String?,
    val date: String,
    /** The account it arrived in; shown only with more than one account. */
    val account: String?,
)

/** Where triage put it (rules only today): pill, folder chip and its reason. */
data class TriageBrief(
    val level: ImportanceLevel,
    val action: VerdictAction,
    val folder: FolderRole,
    val customFolder: String?,
    val reason: String,
    val fromAi: Boolean,
)

sealed interface BodyState {
    data object Loading : BodyState

    data class Loaded(
        val text: String,
        val fromHtml: Boolean,
        val truncated: Boolean,
    ) : BodyState

    data class Failed(
        val problem: Problem,
    ) : BodyState
}

/** Why something couldn't be fetched, in words the screen can show. */
enum class Problem { GONE, NEEDS_SIGN_IN, OFFLINE, TOO_LARGE, NO_APP, REFUSED_TYPE, NOT_FOUND }

data class AttachmentItem(
    val partId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val downloading: Boolean = false,
    val problem: Problem? = null,
)

/** One-shot: the UI opens [file] with an intent, then calls `DetailViewModel.opened`. */
data class OpenRequest(
    val file: File,
    val mimeType: String,
)

data class DetailUiState(
    val loading: Boolean = true,
    /** The message was deleted (by sync) while open. */
    val missing: Boolean = false,
    val header: DetailHeader? = null,
    val triage: TriageBrief? = null,
    /** The AI summary, else the stored preview's start. */
    val brief: String = "",
    val briefFromAi: Boolean = false,
    /** Next steps need the on-device model (T32); hidden while it isn't available. */
    val nextSteps: List<String> = emptyList(),
    val aiAvailable: Boolean = false,
    val body: BodyState = BodyState.Loading,
    val originalExpanded: Boolean = false,
    val attachments: List<AttachmentItem> = emptyList(),
    val open: OpenRequest? = null,
) {
    val showNextSteps: Boolean get() = aiAvailable && nextSteps.isNotEmpty()
}

private const val BRIEF_CHARS = 420

/** The start of the stored preview as a short paragraph: whole lines, at most [BRIEF_CHARS]. */
fun briefFrom(snippet: String): String {
    val text =
        snippet
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(">") }
            .joinToString(" ")
    if (text.length <= BRIEF_CHARS) return text
    val cut = text.take(BRIEF_CHARS)
    val sentence = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
    return if (sentence > BRIEF_CHARS / 2) cut.take(sentence + 1) else cut.trimEnd() + "…"
}

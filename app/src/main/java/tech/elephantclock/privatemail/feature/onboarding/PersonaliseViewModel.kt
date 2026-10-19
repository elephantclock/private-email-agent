package tech.elephantclock.privatemail.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.core.data.onboarding.SentPeople
import tech.elephantclock.privatemail.core.data.onboarding.SentPerson
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.CustomFolder
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.data.settings.FolderNameProblem
import tech.elephantclock.privatemail.core.data.settings.FolderNames
import tech.elephantclock.privatemail.core.data.settings.OnboardingStep
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole

data class PersonaliseUiState(
    val people: List<SentPerson> = emptyList(),
    val prefs: AccountPrefs = AccountPrefs(),
    val reviewingPeople: Boolean = false,
    val customDraft: String = "",
    val customProblem: CustomFolderProblem? = null,
    val work: FirstRunWorkState = FirstRunWorkState(),
) {
    /** Still reading the Sent folder: the people list may grow. */
    val readingSent: Boolean get() = work.running && work.mode == FirstRunWorkState.Mode.PREPARE

    fun isVip(person: SentPerson): Boolean = SentPeople.key(person.email) in prefs.vips
}

enum class CustomFolderProblem { EMPTY, DUPLICATE, TOO_LONG, BAD_CHARACTER }

interface PersonaliseActions {
    fun onReviewPeople()

    fun onToggleVip(email: String)

    fun onToggleFolder(role: FolderRole)

    fun onCustomDraftChange(text: String)

    fun onAddCustomFolder()

    fun onRemoveCustomFolder(name: String)

    fun onDiscardMode(mode: DiscardMode)

    fun onSort()

    object NoActions : PersonaliseActions {
        override fun onReviewPeople() = Unit

        override fun onToggleVip(email: String) = Unit

        override fun onToggleFolder(role: FolderRole) = Unit

        override fun onCustomDraftChange(text: String) = Unit

        override fun onAddCustomFolder() = Unit

        override fun onRemoveCustomFolder(name: String) = Unit

        override fun onDiscardMode(mode: DiscardMode) = Unit

        override fun onSort() = Unit
    }
}

/**
 * Screen 02 (T42). Opening it starts the first-run preparation (sync and the Sent backfill), so
 * the people list fills from Sent while the user chooses. Every choice is saved at once in the
 * settings store; "Sort my last 30 days" moves on to screen 03 and starts the label-only sort.
 */
class PersonaliseViewModel(
    private val account: AccountId,
    private val data: PersonaliseData,
    private val work: FirstRunWork,
) : ViewModel(),
    PersonaliseActions {
    private val _state = MutableStateFlow(PersonaliseUiState())
    val state: StateFlow<PersonaliseUiState> = _state.asStateFlow()

    init {
        work.prepare(account)
        viewModelScope.launch { data.people(account).collect { p -> _state.update { it.copy(people = p) } } }
        viewModelScope.launch { data.prefs(account).collect { p -> _state.update { it.copy(prefs = p) } } }
        viewModelScope.launch { work.state(account).collect { w -> _state.update { it.copy(work = w) } } }
    }

    override fun onReviewPeople() = _state.update { it.copy(reviewingPeople = !it.reviewingPeople) }

    override fun onToggleVip(email: String) {
        val key = SentPeople.key(email)
        save { p -> p.copy(vips = if (key in p.vips) p.vips - key else p.vips + key) }
    }

    override fun onToggleFolder(role: FolderRole) {
        save { p ->
            p.copy(
                disabledFolders =
                    if (role in
                        p.disabledFolders
                    ) {
                        p.disabledFolders - role
                    } else {
                        p.disabledFolders + role
                    },
            )
        }
    }

    override fun onCustomDraftChange(text: String) = _state.update { it.copy(customDraft = text, customProblem = null) }

    override fun onAddCustomFolder() {
        val s = _state.value
        when (val parsed = parseCustomFolder(s.customDraft, s.prefs.customFolders.map { it.name })) {
            is ParsedFolder.Problem -> {
                _state.update { it.copy(customProblem = parsed.problem) }
            }

            is ParsedFolder.Ok -> {
                _state.update { it.copy(customDraft = "", customProblem = null) }
                save { p -> p.copy(customFolders = p.customFolders + parsed.folder) }
            }
        }
    }

    override fun onRemoveCustomFolder(name: String) =
        save { p ->
            p.copy(
                customFolders =
                    p.customFolders.filterNot {
                        it.name ==
                            name
                    },
            )
        }

    override fun onDiscardMode(mode: DiscardMode) = save { it.copy(discardMode = mode) }

    override fun onSort() {
        viewModelScope.launch {
            data.update(account) { it.copy(step = OnboardingStep.FIRST_RUN) }
            work.sort(account)
        }
    }

    private fun save(transform: (AccountPrefs) -> AccountPrefs) {
        // Shown at once; the store's flow confirms it.
        _state.update { it.copy(prefs = transform(it.prefs)) }
        viewModelScope.launch { data.update(account, transform) }
    }

    companion object {
        const val MAX_NAME = 40
    }
}

internal sealed interface ParsedFolder {
    data class Ok(
        val folder: CustomFolder,
    ) : ParsedFolder

    data class Problem(
        val problem: CustomFolderProblem,
    ) : ParsedFolder
}

/**
 * "Partners: anyone asking to integrate or resell" → folder "Partners" with that description.
 * Without a colon the whole text describes the folder and its first words name it.
 */
internal fun parseCustomFolder(
    text: String,
    existing: List<String>,
): ParsedFolder {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ParsedFolder.Problem(CustomFolderProblem.EMPTY)
    val colon = trimmed.indexOf(':')
    val name =
        if (colon > 0) {
            trimmed.substring(0, colon).trim()
        } else {
            trimmed.split(Regex("\\s+")).take(3).joinToString(" ")
        }
    val description = if (colon > 0) trimmed.substring(colon + 1).trim().ifEmpty { name } else trimmed
    if (name.isEmpty()) return ParsedFolder.Problem(CustomFolderProblem.EMPTY)
    if (name.length > PersonaliseViewModel.MAX_NAME) return ParsedFolder.Problem(CustomFolderProblem.TOO_LONG)
    val taken = existing + RESERVED
    if (taken.any { it.equals(name, ignoreCase = true) }) return ParsedFolder.Problem(CustomFolderProblem.DUPLICATE)
    // Same rules as the Rules screen (T48): no hierarchy separators or wildcards, which would
    // create nested or unreachable folders on the server.
    when (FolderNames.checkName(name, existing)) {
        FolderNameProblem.BAD_CHARACTER -> return ParsedFolder.Problem(CustomFolderProblem.BAD_CHARACTER)

        FolderNameProblem.DUPLICATE, FolderNameProblem.RESERVED -> return ParsedFolder.Problem(
            CustomFolderProblem.DUPLICATE,
        )

        FolderNameProblem.TOO_LONG -> return ParsedFolder.Problem(CustomFolderProblem.TOO_LONG)

        FolderNameProblem.EMPTY -> return ParsedFolder.Problem(CustomFolderProblem.EMPTY)

        null -> Unit
    }
    return ParsedFolder.Ok(CustomFolder(name, description))
}

/** Folder names triage already uses. */
private val RESERVED =
    listOf(
        "Inbox",
        "Priority",
        "People",
        "Money",
        "Work updates",
        "Orders & travel",
        "Reports & alerts",
        "Discarded",
        "Junk",
        "Sent",
        "Trash",
        "Drafts",
    )

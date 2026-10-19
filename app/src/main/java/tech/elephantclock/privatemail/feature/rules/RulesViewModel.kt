package tech.elephantclock.privatemail.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.data.settings.FolderNames
import tech.elephantclock.privatemail.core.data.settings.ImportResult
import tech.elephantclock.privatemail.core.data.settings.RuleEditResult
import tech.elephantclock.privatemail.core.data.settings.RulesImport
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import java.util.Locale

/**
 * Screen 07 (T48), per account: folder switches and descriptions, learned rules, alert levels,
 * the digest, discard mode, trusted authserv-ids and export/import. Every change is saved at
 * once in the settings store (the triage reads it on its next drain); edits to learned rules
 * go through `LearnedRuleEdits`, which keeps their invariants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModel(
    private val data: RulesData,
    private val files: RulesFiles,
    private val io: CoroutineDispatcher,
) : ViewModel(),
    RulesActions {
    private val _state = MutableStateFlow(RulesUiState())
    val state: StateFlow<RulesUiState> = _state.asStateFlow()
    private val selected = MutableStateFlow<AccountId?>(null)

    init {
        viewModelScope.launch {
            data.accounts().collect { accounts ->
                val kept = selected.value?.takeIf { id -> accounts.any { it.id == id } }
                val current = kept ?: accounts.firstOrNull()?.id
                val loading = current != null && _state.value.loading
                _state.update { it.copy(accounts = accounts, selected = current, loading = loading) }
                if (accounts.isEmpty()) _state.update { it.copy(loading = false) }
                selected.value = current
            }
        }
        viewModelScope.launch {
            selected.filterNotNull().flatMapLatest(::load).collect { l ->
                _state.update {
                    if (it.selected != l.id) {
                        it
                    } else {
                        it.copy(loading = false, prefs = l.prefs, rules = l.rules, hasTrash = l.hasTrash, gate = l.gate)
                    }
                }
            }
        }
        viewModelScope.launch {
            data.digest().distinctUntilChanged().collect { d -> _state.update { it.copy(digest = d) } }
        }
    }

    private fun load(id: AccountId) =
        combine(data.prefs(id), data.rules(id), data.hasTrash(id)) { prefs, rules, trash ->
            Loaded(id, prefs, rules, trash, data.gate(id))
        }

    private data class Loaded(
        val id: AccountId,
        val prefs: AccountPrefs,
        val rules: List<RuleRow>,
        val hasTrash: Boolean,
        val gate: BackfillStatus,
    )

    override fun onSelectAccount(id: AccountId) {
        if (id == selected.value) return
        _state.update { it.copy(selected = id, loading = true, dialog = null, authservDraft = "") }
        selected.value = id
    }

    override fun onToggleFolder(role: FolderRole) {
        if (role == FolderRole.PRIORITY) return
        save { p -> p.copy(disabledFolders = p.disabledFolders.toggle(role)) }
    }

    override fun onToggleCustom(name: String) =
        save { p ->
            p.copy(customFolders = p.customFolders.map { if (it.name == name) it.copy(enabled = !it.enabled) else it })
        }

    override fun onRemoveCustom(name: String) {
        _state.update { it.copy(dialog = null) }
        save { p -> p.copy(customFolders = p.customFolders.filterNot { it.name == name }) }
    }

    override fun onEditFolder(target: FolderTarget) {
        val prefs = _state.value.prefs
        val current =
            if (target.role == FolderRole.CUSTOM) {
                prefs.customFolders.firstOrNull { it.name == target.customName }?.description ?: return
            } else {
                prefs.folderDescriptions[target.role].orEmpty()
            }
        _state.update { it.copy(dialog = RulesDialog.EditFolder(target, current)) }
    }

    override fun onAddFolder() {
        if (_state.value.prefs.customFolders.size >= FolderNames.MAX_CUSTOM_FOLDERS) return
        _state.update { it.copy(dialog = RulesDialog.AddFolder()) }
    }

    override fun onDialogChange(dialog: RulesDialog) = _state.update { it.copy(dialog = dialog) }

    override fun onDismissDialog() = _state.update { it.copy(dialog = null) }

    override fun onSaveDialog() {
        when (val d = _state.value.dialog) {
            is RulesDialog.EditFolder -> saveFolder(d)
            is RulesDialog.AddFolder -> addFolder(d)
            is RulesDialog.ConfirmImport -> applyImport(d)
            is RulesDialog.Retarget, null -> _state.update { it.copy(dialog = null) }
        }
    }

    private fun saveFolder(d: RulesDialog.EditFolder) {
        val text = d.draft.trim()
        // An empty built-in description means "back to the default one".
        val builtIn = d.target.role != FolderRole.CUSTOM
        if (!(builtIn && text.isEmpty())) {
            val problem = FolderNames.checkDescription(text)
            if (problem != null) return _state.update { it.copy(dialog = d.copy(problem = problem)) }
        }
        _state.update { it.copy(dialog = null) }
        save { p ->
            if (builtIn) {
                p.copy(
                    folderDescriptions =
                        if (text.isEmpty()) {
                            p.folderDescriptions - d.target.role
                        } else {
                            p.folderDescriptions + (d.target.role to text)
                        },
                )
            } else {
                val name = d.target.customName
                p.copy(customFolders = p.customFolders.map { if (it.name == name) it.copy(description = text) else it })
            }
        }
    }

    private fun addFolder(d: RulesDialog.AddFolder) {
        val existing =
            _state.value.prefs.customFolders
                .map { it.name }
        val problem = FolderNames.checkName(d.name, existing) ?: FolderNames.checkDescription(d.description)
        if (problem != null) return _state.update { it.copy(dialog = d.copy(problem = problem)) }
        _state.update { it.copy(dialog = null) }
        val folder =
            tech.elephantclock.privatemail.core.data.settings
                .CustomFolder(d.name.trim(), d.description.trim())
        save { p ->
            val taken = p.customFolders.any { it.name.equals(folder.name, true) }
            if (taken) p else p.copy(customFolders = p.customFolders + folder)
        }
    }

    override fun onRuleClick(rule: RuleRow) = _state.update { it.copy(dialog = RulesDialog.Retarget(rule)) }

    override fun onRetarget(
        rule: RuleRow,
        target: FolderTarget,
    ) {
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            when (data.retarget(rule.id, target)) {
                RuleEditResult.VIP_RULE -> notice(RulesNotice.VipRuleFixed)
                RuleEditResult.GONE, RuleEditResult.INVALID -> notice(RulesNotice.RuleGone)
                RuleEditResult.DONE -> Unit
            }
        }
    }

    override fun onDeleteRule(rule: RuleRow) {
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch { data.deleteRule(rule.id) }
    }

    override fun onAlertUrgent(on: Boolean) = save { it.copy(alertUrgent = on) }

    override fun onAlertHigh(on: Boolean) = save { it.copy(alertHigh = on) }

    override fun onDigest(on: Boolean) {
        _state.update { it.copy(digest = on) }
        viewModelScope.launch { data.setDigest(on) }
    }

    override fun onDiscardMode(mode: DiscardMode) = save { it.copy(discardMode = mode) }

    override fun onToggleAdvanced() = _state.update { it.copy(showAdvanced = !it.showAdvanced) }

    override fun onAuthservDraft(text: String) = _state.update { it.copy(authservDraft = text.take(MAX_AUTHSERV)) }

    override fun onAddAuthserv() {
        val id =
            _state.value.authservDraft
                .trim()
                .lowercase(Locale.ROOT)
        val ok = id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
        if (!ok || _state.value.prefs.trustedAuthservIds.size >= RulesImport.MAX_AUTHSERV_IDS) return
        _state.update { it.copy(authservDraft = "") }
        save { it.copy(trustedAuthservIds = it.trustedAuthservIds + id) }
    }

    override fun onRemoveAuthserv(id: String) = save { it.copy(trustedAuthservIds = it.trustedAuthservIds - id) }

    override fun onExportTo(uri: String) {
        val account = selected.value ?: return
        viewModelScope.launch {
            val ok = runCatching { withContext(io) { files.write(uri, data.export(account)) } }.getOrDefault(false)
            notice(if (ok) RulesNotice.Exported else RulesNotice.ExportFailed)
        }
    }

    override fun onImportFrom(uri: String) {
        val account = selected.value ?: return
        viewModelScope.launch {
            val bytes = withContext(io) { files.read(uri, RulesImport.MAX_BYTES) }
            if (bytes == null) return@launch notice(RulesNotice.ImportUnreadable)
            when (val result = withContext(io) { data.parseImport(bytes, account) }) {
                is ImportResult.Invalid -> notice(RulesNotice.ImportRefused(result.problem))
                is ImportResult.Ok -> _state.update { it.copy(dialog = RulesDialog.ConfirmImport(result.plan)) }
            }
        }
    }

    private fun applyImport(d: RulesDialog.ConfirmImport) {
        val account = selected.value ?: return
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            data.applyImport(d.plan, account, d.trustAuthservIds)
            notice(RulesNotice.Imported)
        }
    }

    override fun onNoticeShown() = _state.update { it.copy(notice = null) }

    private fun notice(n: RulesNotice) = _state.update { it.copy(notice = n) }

    private fun save(transform: (AccountPrefs) -> AccountPrefs) {
        val account = selected.value ?: return
        _state.update { it.copy(prefs = transform(it.prefs)) }
        viewModelScope.launch { data.update(account, transform) }
    }

    private companion object {
        const val MAX_AUTHSERV = 100
    }
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

package tech.elephantclock.privatemail.feature.rules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.settings.ImportProblem
import tech.elephantclock.privatemail.ui.components.FolderChip
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Screen 07 wired to its ViewModel and the system file picker (export: create a document; import: open one). */
@Composable
fun RulesRoute(
    viewModel: RulesViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fileName = stringResource(R.string.rules_export_file_name)
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { viewModel.onExportTo(it.toString()) }
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onImportFrom(it.toString()) }
        }
    RulesScreen(
        state = state,
        actions = viewModel,
        onExport = { export.launch(fileName) },
        onImport = { import.launch(arrayOf("application/json", "text/plain")) },
        modifier = modifier,
    )
}

/**
 * Screen 07 (mockup "Folders and rules"): dark header with account chips; the folders with
 * their descriptions (the AI reads them), learned rules, alert levels; then discarded mail,
 * auto-file state, advanced settings and backup. Two columns on wide windows.
 */
@Composable
fun RulesScreen(
    state: RulesUiState,
    actions: RulesActions,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.notice?.let { stringResource(noticeText(it)) }
    LaunchedEffect(state.notice) {
        if (message == null) return@LaunchedEffect
        snackbar.showSnackbar(message)
        actions.onNoticeShown()
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Header(state, actions)
            if (!state.loading && state.accounts.isEmpty()) {
                Text(
                    stringResource(R.string.rules_no_accounts),
                    style = MaterialTheme.typography.bodyLarge,
                    color = BrandTheme.colors.textMuted,
                    modifier = Modifier.padding(20.dp),
                )
            } else if (state.selected != null) {
                Body(state, actions, onExport, onImport)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
    RulesDialogHost(state, actions)
}

@Composable
private fun Body(
    state: RulesUiState,
    actions: RulesActions,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val first: @Composable ColumnScope.() -> Unit = {
            Section(R.string.rules_section_folders) { FoldersCard(state.prefs, actions) }
            Section(R.string.rules_section_learned) { LearnedCard(state.rules, actions) }
        }
        val second: @Composable ColumnScope.() -> Unit = {
            Section(R.string.rules_section_alerts) { AlertsCard(state.prefs, state.digest, actions) }
            Section(R.string.rules_section_discard) { DiscardCard(state, actions) }
            Section(R.string.rules_section_auto_file) { AutoFileCard(state.autoFile) }
            Section(R.string.rules_section_advanced) { AdvancedCard(state, actions) }
            Section(R.string.rules_section_backup) { BackupCard(onExport, onImport) }
            Footer(onExport)
        }
        if (maxWidth >= TWO_COLUMNS) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = first)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = second)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                first()
                second()
            }
        }
    }
}

@Composable
private fun Section(
    label: Int,
    content: @Composable () -> Unit,
) {
    SectionLabel(stringResource(label), Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp))
    Box(Modifier.padding(horizontal = 16.dp)) { content() }
}

@Composable
private fun Header(
    state: RulesUiState,
    actions: RulesActions,
) {
    val brand = BrandTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(brand.header)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.rules_title),
            style = MaterialTheme.typography.headlineSmall,
            color = brand.onHeader,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.accounts.forEach { account ->
                FolderChip(
                    account.label,
                    selected = account.id == state.selected,
                    onClick = { actions.onSelectAccount(account.id) },
                )
            }
        }
    }
}

@Composable
private fun Footer(onExport: () -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .background(BrandTheme.colors.accent)
                .padding(top = 1.dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "elephantclock.ae",
                style = MaterialTheme.typography.labelMedium,
                color = BrandTheme.colors.sectionLabel,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onExport) {
                Text(
                    stringResource(R.string.rules_export),
                    color = linkColor(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private val TWO_COLUMNS = 700.dp

internal fun noticeText(n: RulesNotice): Int =
    when (n) {
        RulesNotice.Exported -> {
            R.string.rules_notice_exported
        }

        RulesNotice.ExportFailed -> {
            R.string.rules_notice_export_failed
        }

        RulesNotice.Imported -> {
            R.string.rules_notice_imported
        }

        RulesNotice.ImportUnreadable -> {
            R.string.rules_notice_import_unreadable
        }

        RulesNotice.VipRuleFixed -> {
            R.string.rules_notice_vip
        }

        RulesNotice.RuleGone -> {
            R.string.rules_notice_rule_gone
        }

        is RulesNotice.ImportRefused -> {
            when (n.problem) {
                ImportProblem.TOO_LARGE -> R.string.rules_notice_import_too_large
                ImportProblem.NOT_JSON, ImportProblem.WRONG_FORMAT -> R.string.rules_notice_import_invalid
                ImportProblem.NEWER_VERSION -> R.string.rules_notice_import_newer
                else -> R.string.rules_notice_import_bad_content
            }
        }
    }

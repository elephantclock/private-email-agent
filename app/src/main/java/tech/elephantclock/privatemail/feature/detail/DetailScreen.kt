package tech.elephantclock.privatemail.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.feature.shell.HingeSplit
import tech.elephantclock.privatemail.feature.shell.LocalShellNavigation
import tech.elephantclock.privatemail.ui.theme.BrandTheme

@Composable
fun DetailRoute(
    viewModel: DetailViewModel,
    onReply: (() -> Unit)?,
    modifier: Modifier = Modifier,
    belowHinge: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The brief is asked for only while this email is on screen (GenAI is foreground-only).
    LaunchedEffect(viewModel) { viewModel.watchBrief() }
    LaunchedEffect(state.open) {
        val request = state.open ?: return@LaunchedEffect
        viewModel.opened(AttachmentFiles.open(context, request.file, request.mimeType))
    }
    DetailScreen(
        state = state,
        onToggleOriginal = viewModel::toggleOriginal,
        onRetry = viewModel::retryBody,
        onAttachment = viewModel::openAttachment,
        onReply = onReply,
        modifier = modifier,
        belowHinge = belowHinge,
    )
}

/**
 * The open email (mockups 08, 09): chips, subject, brief, next steps (AI only), then the original
 * email collapsed and the attachments. In Flex mode the reading part sits above the hinge and the
 * Reply action below it, or [belowHinge] in its place (the compact reply editor, mockup 10).
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    onToggleOriginal: () -> Unit,
    onRetry: () -> Unit,
    onAttachment: (String) -> Unit,
    onReply: (() -> Unit)?,
    modifier: Modifier = Modifier,
    belowHinge: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val navigation = LocalShellNavigation.current
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("detail")) {
        HingeSplit(
            top = {
                if (navigation.singlePane) BackBar(navigation.back)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .then(if (navigation.singlePane) Modifier else Modifier.windowInsetsPadding(topInsets))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { Reading(state, onToggleOriginal, onRetry, onAttachment) }
            },
            bottom = {
                when {
                    belowHinge != null -> belowHinge()
                    onReply != null && state.header != null -> ActionBar(onReply)
                }
            },
        )
    }
}

private val topInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

@Composable
private fun Reading(
    state: DetailUiState,
    onToggleOriginal: () -> Unit,
    onRetry: () -> Unit,
    onAttachment: (String) -> Unit,
) {
    if (state.missing) {
        Text(stringResource(R.string.detail_missing), style = MaterialTheme.typography.bodyLarge)
        return
    }
    val header = state.header ?: return
    BriefChips(state.triage, state.attachments.size)
    Text(
        header.subject,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        "${header.fromName ?: header.fromEmail} · ${header.date}",
        style = MaterialTheme.typography.bodyMedium,
        color = BrandTheme.colors.textMuted,
    )
    BriefBlock(state)
    state.triage?.let {
        Text(
            stringResource(R.string.detail_why, it.reason),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTheme.colors.textMuted,
        )
    }
    if (state.showNextSteps) NextSteps(state.nextSteps)
    if (!state.aiAvailable) {
        Text(
            stringResource(R.string.detail_ai_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTheme.colors.textMuted,
            modifier = Modifier.testTag("ai_unavailable"),
        )
    }
    OriginalEmail(state, onToggleOriginal, onRetry)
    Attachments(state.attachments, onAttachment)
}

@Composable
private fun BackBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BrandTheme.colors.header)
            .windowInsetsPadding(topInsets)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.detail_back),
                tint = BrandTheme.colors.onHeader,
            )
        }
    }
}

@Composable
private fun ActionBar(onReply: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(
            onClick = onReply,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = BrandTheme.colors.accent,
                    contentColor = BrandTheme.colors.onAccent,
                ),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 120.dp).testTag("reply_button"),
        ) { Text(stringResource(R.string.detail_reply), fontWeight = FontWeight.Bold) }
    }
}

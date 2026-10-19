package tech.elephantclock.privatemail.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Dark title bar of a list pane (mockups 04, 08): title plus a gold subtitle. */
@Composable
fun ShellHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(BrandTheme.colors.header)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = BrandTheme.colors.onHeader,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleSmall,
                color = BrandTheme.colors.accent,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

/** Until the real screens land (T44 onwards): a header and a one-line explanation. */
@Composable
fun PlaceholderList(
    destination: ShellDestination,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ShellHeader(
            title = stringResource(destination.label),
            subtitle =
                if (destination ==
                    ShellDestination.Priority
                ) {
                    stringResource(R.string.shell_all_accounts)
                } else {
                    null
                },
        )
        EmptyMessage(stringResource(destination.emptyText))
    }
}

/** Shown in the detail pane while nothing is open. */
@Composable
fun PlaceholderDetail(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        EmptyMessage(stringResource(R.string.shell_detail_empty))
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = BrandTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}

private val ShellDestination.emptyText: Int
    get() =
        when (this) {
            ShellDestination.Priority -> R.string.shell_priority_empty
            ShellDestination.Folders -> R.string.shell_folders_empty
            ShellDestination.Discarded -> R.string.shell_discarded_empty
            ShellDestination.Rules -> R.string.shell_rules_empty
        }

/** "x@y needs your password again" with a Sign in button; sync for that account is paused. */
@Composable
fun AuthProblemBanner(
    email: String,
    onSignIn: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BrandTheme.colors.urgent)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.shell_auth_problem, email),
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTheme.colors.onUrgent,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onSignIn, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.shell_auth_problem_action), color = BrandTheme.colors.onUrgent)
        }
    }
}

package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.feature.shell.rememberWindowInfo
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Screen 02 wired to its view model. */
@Composable
fun PersonaliseRoute(
    viewModel: PersonaliseViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PersonaliseScreen(state, viewModel, modifier)
}

/**
 * What matters to you (mockup 02): people from Sent (mark VIPs), the value folders with their
 * plain-language descriptions, a custom folder described in plain words, the discard mode, and
 * "Sort my last 30 days".
 */
@Composable
fun PersonaliseScreen(
    state: PersonaliseUiState,
    actions: PersonaliseActions,
    modifier: Modifier = Modifier,
    windowInfo: WindowInfo = rememberWindowInfo(),
) {
    OnboardingFrame(
        windowInfo = windowInfo,
        modifier = modifier,
        header = {
            StepTitle(
                stringResource(R.string.personalise_step),
                stringResource(R.string.personalise_title),
                stringResource(R.string.personalise_intro),
            )
        },
        actions = {
            Button(
                onClick = actions::onSort,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium,
                colors =
                    if (!windowInfo.actionsOnDark) {
                        ButtonDefaults.buttonColors()
                    } else {
                        // On the dark side panel the button is gold, like the mockups' accents.
                        ButtonDefaults.buttonColors(
                            containerColor = BrandTheme.colors.accent,
                            contentColor = BrandTheme.colors.onAccent,
                        )
                    },
            ) {
                Text(stringResource(R.string.personalise_sort), style = MaterialTheme.typography.titleMedium)
            }
        },
    ) {
        SectionLabel(stringResource(R.string.personalise_people_label), SectionLabelPadding)
        PeopleCard(state, actions, CardPadding)
        SectionLabel(stringResource(R.string.personalise_folders_label), SectionLabelPadding)
        ValueFoldersCard(state.prefs, actions, CardPadding)
        CustomFolderBox(state, actions, CardPadding.then(Modifier))
        SectionLabel(stringResource(R.string.personalise_discard_label), SectionLabelPadding, danger = true)
        DiscardModeCard(state.prefs.discardMode, actions, CardPadding)
    }
}

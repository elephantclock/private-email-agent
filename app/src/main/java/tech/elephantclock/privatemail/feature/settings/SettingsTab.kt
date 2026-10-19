package tech.elephantclock.privatemail.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R

/** The Rules tab: the account's rules (T48) and the accounts themselves (T52), one at a time. */
@Composable
fun SettingsTab(
    rules: @Composable () -> Unit,
    accounts: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showAccounts,
                    onClick = { showAccounts = false },
                    label = { Text(stringResource(R.string.settings_tab_rules)) },
                )
                FilterChip(
                    selected = showAccounts,
                    onClick = { showAccounts = true },
                    label = { Text(stringResource(R.string.settings_tab_accounts)) },
                )
            }
            if (showAccounts) accounts() else rules()
        }
    }
}

package tech.elephantclock.privatemail.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Synthetic list and detail content, to show the shell's panes in tests (no real mail). */
val sampleItems = listOf("Server monitor", "Customer support", "Prospective client", "Bitbucket", "Informap")

val sampleList: ShellListPane = { destination, selected, onOpen ->
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ShellHeader(title = "${destination.name} list", subtitle = "· sample")
        sampleItems.forEach { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (item ==
                            selected
                        ) {
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        } else {
                            MaterialTheme.colorScheme.background
                        },
                    ).clickable { onOpen(item) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("item:$item"),
            ) {
                Text(item, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Synthetic sample row",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTheme.colors.textMuted,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

val sampleDetail: ShellDetailPane = { _, key ->
    HingeSplit(
        modifier = Modifier.testTag("detail:$key"),
        top = {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(key, style = MaterialTheme.typography.headlineSmall)
                Text("The open email reads above the hinge in Flex mode.", style = MaterialTheme.typography.bodyLarge)
            }
        },
        bottom = {
            Text(
                "Reply and actions sit below the hinge.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(24.dp),
            )
        },
    )
}

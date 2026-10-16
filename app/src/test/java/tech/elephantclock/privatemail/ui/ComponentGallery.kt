package tech.elephantclock.privatemail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.ui.components.BrandCard
import tech.elephantclock.privatemail.ui.components.BrandCardStyle
import tech.elephantclock.privatemail.ui.components.BrandSwitch
import tech.elephantclock.privatemail.ui.components.FolderChip
import tech.elephantclock.privatemail.ui.components.ImportanceLevel
import tech.elephantclock.privatemail.ui.components.ImportancePill
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.Brand
import tech.elephantclock.privatemail.ui.theme.BrandTheme
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

/** Every T02 component on one screen, laid out like the mockups. */
@Composable
fun ComponentGallery(darkTheme: Boolean) {
    PrivateMailTheme(darkTheme = darkTheme) {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            Column(
                Modifier.fillMaxWidth().background(BrandTheme.colors.header).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Priority", color = BrandTheme.colors.onHeader, style = MaterialTheme.typography.headlineMedium)
                Row {
                    FolderChip("All · 5", selected = true, onClick = {})
                    FolderChip("izi.travel", selected = false, onClick = {}, dotColor = Brand.Cognac)
                    FolderChip("elephantclock", selected = false, onClick = {}, dotColor = Brand.Beige)
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Importance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportanceLevel.entries.forEach { ImportancePill(it) }
                }
                SectionLabel("Value folders")
                BrandCard(
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(horizontal = 16.dp),
                ) {
                    SwitchRow("People", "Real conversations", checked = true)
                    HorizontalDivider(color = BrandTheme.colors.divider)
                    SwitchRow("Reports & alerts", "Automated but useful", checked = false)
                    HorizontalDivider(color = BrandTheme.colors.divider)
                    SwitchRow("Disabled", "Not available", checked = true, enabled = false)
                }
                BrandCard(style = BrandCardStyle.Highlight) {
                    Text(
                        "Today: 38 filed, 61 discarded.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Nothing else needs you.", style = MaterialTheme.typography.bodyMedium)
                }
                SectionLabel("Discarded mail", danger = true)
                BrandCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ImportancePill(ImportanceLevel.Urgent)
                        Spacer(Modifier.width(8.dp))
                        Text("Informap", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("15:02", style = MaterialTheme.typography.bodySmall, color = BrandTheme.colors.textMuted)
                    }
                    Text("Q3 partner payouts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Asks you to confirm payout figures by Monday.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = BrandTheme.colors.textMuted)
        }
        BrandSwitch(checked = checked, onCheckedChange = {}, enabled = enabled)
    }
}

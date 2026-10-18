package tech.elephantclock.privatemail.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/**
 * Dark list header (mockups 04, 08): title, gold subtitle and a round search button; below it
 * a scrolling row of [chips]. While [searching], the title row becomes a search field.
 */
@Composable
fun InboxHeader(
    title: String,
    subtitle: String?,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    modifier: Modifier = Modifier,
    chips: @Composable () -> Unit = {},
) {
    val brand = BrandTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(brand.header)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searching) {
                SearchField(query, onQuery, Modifier.weight(1f))
                HeaderButton(Icons.Filled.Close, stringResource(R.string.inbox_search_close), onCloseSearch)
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = brand.onHeader,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = brand.accent,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp, bottom = 3.dp),
                        )
                    }
                }
                HeaderButton(Icons.Filled.Search, stringResource(R.string.inbox_search), onOpenSearch)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) { chips() }
    }
}

@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = BrandTheme.colors.headerOutline.copy(alpha = 0.45f),
                contentColor = BrandTheme.colors.onHeader,
            ),
        shape = CircleShape,
        modifier = Modifier.size(48.dp),
    ) { Icon(icon, contentDescription = description) }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val brand = BrandTheme.colors
    TextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.inbox_search_hint), color = brand.onHeaderMuted) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = brand.header,
                unfocusedContainerColor = brand.header,
                focusedTextColor = brand.onHeader,
                unfocusedTextColor = brand.onHeader,
                cursorColor = brand.accent,
                focusedIndicatorColor = brand.accent,
                unfocusedIndicatorColor = brand.headerOutline,
            ),
        modifier = modifier.focusRequester(focus).testTag("search_field"),
    )
}

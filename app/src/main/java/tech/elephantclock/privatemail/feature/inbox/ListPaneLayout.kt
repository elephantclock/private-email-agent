package tech.elephantclock.privatemail.feature.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.elephantclock.privatemail.feature.shell.FoldPosture
import tech.elephantclock.privatemail.feature.shell.HingeSplit
import tech.elephantclock.privatemail.feature.shell.LocalFoldPosture

/**
 * The frame of the list panes. Flat: [header], [notices], then the list (with the "today" card
 * at its end). Flex mode (T4A): the list reads above the hinge; the header with search and the
 * account/folder chips, the notices and [today] sit below it, where the hands are.
 */
@Composable
internal fun ListPaneLayout(
    modifier: Modifier,
    header: @Composable () -> Unit,
    notices: @Composable ColumnScope.() -> Unit,
    today: (@Composable () -> Unit)?,
    list: @Composable (showToday: Boolean) -> Unit,
) {
    if (LocalFoldPosture.current !is FoldPosture.Tabletop) {
        Column(modifier.fillMaxSize()) {
            header()
            notices()
            list(true)
        }
        return
    }
    val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    HingeSplit(
        modifier = modifier,
        bottomColor = MaterialTheme.colorScheme.background,
        top = { Column(Modifier.fillMaxSize().windowInsetsPadding(topInsets)) { list(false) } },
        bottom = {
            // The header is below the hinge now: the status bar inset belongs to the list.
            Column(Modifier.fillMaxWidth().consumeWindowInsets(topInsets)) {
                header()
                notices()
                today?.invoke()
            }
        },
    )
}

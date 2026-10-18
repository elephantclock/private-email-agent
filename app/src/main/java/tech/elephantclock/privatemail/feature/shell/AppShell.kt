package tech.elephantclock.privatemail.feature.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemColors
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.MutableThreePaneScaffoldState
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** The four top-level places. Order is the navigation order. */
enum class ShellDestination(
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    Priority(R.string.nav_priority, Icons.Filled.Star),
    Folders(R.string.nav_folders, Icons.AutoMirrored.Filled.List),
    Discarded(R.string.nav_discarded, Icons.Filled.Delete),
    Rules(R.string.nav_rules, Icons.Filled.Settings),
}

/** The fold posture the shell is laid out for; read by [HingeSplit]. */
val LocalFoldPosture = staticCompositionLocalOf<FoldPosture> { FoldPosture.Flat }

/** List pane content: [selectedKey] is the open item, [onOpen] shows an item in the detail pane. */
typealias ShellListPane = @Composable (
    destination: ShellDestination,
    selectedKey: String?,
    onOpen: (String) -> Unit,
) -> Unit

/** Detail pane content for the item [key] of [destination]. */
typealias ShellDetailPane = @Composable (destination: ShellDestination, key: String) -> Unit

/**
 * The app's frame: a bottom bar on the cover screen and the inner screen in portrait or Flex
 * mode, a rail in landscape; list and detail side by side from 600 dp. The destination and the
 * open item are saveable, so they survive fold, unfold and rotation (the activity is recreated).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    windowInfo: WindowInfo = rememberWindowInfo(),
    listPane: ShellListPane = { destination, _, _ -> PlaceholderList(destination) },
    detailPane: ShellDetailPane = { _, _ -> PlaceholderDetail() },
    /** Opens this item of the first destination at start (previews and screenshots). */
    initialOpenKey: String? = null,
) {
    var destination by rememberSaveable { mutableStateOf(ShellDestination.Priority) }
    val navigationType = windowInfo.navigationType
    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteType = navigationType,
        navigationSuiteColors = shellNavigationColors(),
        containerColor = MaterialTheme.colorScheme.background,
        navigationItems = {
            ShellDestination.entries.forEach { item ->
                NavigationSuiteItem(
                    selected = item == destination,
                    onClick = { destination = item },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.label)) },
                    navigationSuiteType = navigationType,
                    colors = shellItemColors(navigationType),
                )
            }
        },
    ) {
        CompositionLocalProvider(LocalFoldPosture provides windowInfo.posture) {
            // A fresh navigator per destination: switching tabs closes the open item.
            key(destination) {
                DestinationPanes(
                    destination,
                    windowInfo,
                    listPane,
                    detailPane,
                    initialOpenKey.takeIf { destination == ShellDestination.entries.first() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun DestinationPanes(
    destination: ShellDestination,
    windowInfo: WindowInfo,
    listPane: ShellListPane,
    detailPane: ShellDetailPane,
    initialOpenKey: String?,
) {
    val density = LocalDensity.current.density
    val directive = shellDirective(windowInfo, density)
    val navigator =
        rememberListDetailPaneScaffoldNavigator(
            scaffoldDirective = directive,
            initialDestinationHistory =
                listOfNotNull(
                    ThreePaneScaffoldDestinationItem<String>(ListDetailPaneScaffoldRole.List),
                    initialOpenKey?.let { ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail, it) },
                ),
        )
    // The navigator only re-lays out on navigation; a resize without recreation (multi-window,
    // DeX, a test) must apply the new directive too, e.g. one pane → two on unfolding.
    LaunchedEffect(directive) {
        (navigator.scaffoldState as? MutableThreePaneScaffoldState)?.snapTo(navigator.scaffoldValue)
    }
    val scope = rememberCoroutineScope()
    val openKey = navigator.currentDestination?.contentKey
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                listPane(destination, openKey) { key ->
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key) }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                if (openKey != null) detailPane(destination, openKey) else PlaceholderDetail()
            }
        },
    )
}

/**
 * Two panes from 600 dp (the inner screen in either orientation), one on the cover screen. In
 * Flex mode one pane, split at the hinge by [HingeSplit], as in the mockup (10).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun shellDirective(
    windowInfo: WindowInfo,
    density: Float,
): PaneScaffoldDirective {
    val base = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(windowInfo.toAdaptiveInfo(density))
    return if (windowInfo.isTabletop) base.copy(maxHorizontalPartitions = 1) else base
}

@Composable
private fun shellNavigationColors() =
    NavigationSuiteDefaults.colors(
        shortNavigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shortNavigationBarContentColor = BrandTheme.colors.textMuted,
        wideNavigationRailColors =
            WideNavigationRailDefaults.colors(
                containerColor = BrandTheme.colors.header,
                contentColor = BrandTheme.colors.onHeaderMuted,
            ),
    )

/** Bar: beige pill on white (mockup 08). Rail: gold pill on dark green (mockup 09). */
@Composable
private fun shellItemColors(type: NavigationSuiteType): NavigationItemColors {
    val brand = BrandTheme.colors
    val scheme = MaterialTheme.colorScheme
    return if (type == NavigationSuiteType.WideNavigationRailCollapsed) {
        NavigationItemColors(
            selectedIconColor = brand.onAccent,
            selectedTextColor = brand.onHeader,
            selectedIndicatorColor = brand.accent,
            unselectedIconColor = brand.onHeaderMuted,
            unselectedTextColor = brand.onHeaderMuted,
            disabledIconColor = brand.onHeaderMuted,
            disabledTextColor = brand.onHeaderMuted,
        )
    } else {
        NavigationItemColors(
            selectedIconColor = scheme.onPrimaryContainer,
            selectedTextColor = scheme.onPrimaryContainer,
            selectedIndicatorColor = scheme.primaryContainer,
            unselectedIconColor = brand.textMuted,
            unselectedTextColor = brand.textMuted,
            disabledIconColor = brand.textMuted,
            disabledTextColor = brand.textMuted,
        )
    }
}

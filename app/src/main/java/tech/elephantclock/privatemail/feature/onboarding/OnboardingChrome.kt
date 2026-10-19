package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.ui.theme.Brand
import tech.elephantclock.privatemail.ui.theme.BrandTheme
import tech.elephantclock.privatemail.ui.theme.DarkBrandColors

/**
 * The frame of screens 02 and 03. Cover screen: the dark header on top, the content scrolling
 * under it and the actions pinned to the bottom. Inner screen (and Flex): the header as a side
 * panel with the actions at its foot, the content beside it capped at 560 dp.
 */
@Composable
internal fun OnboardingFrame(
    windowInfo: WindowInfo,
    header: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (windowInfo.isCompactWidth) {
            Column(Modifier.fillMaxSize().imePadding()) {
                HeaderBlock(Modifier.fillMaxWidth(), header)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                    content = content,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.4f).fillMaxHeight().background(BrandTheme.colors.header)) {
                    HeaderBlock(Modifier.fillMaxWidth().weight(1f), header)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start),
                            ).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = actions,
                    )
                }
                Box(Modifier.weight(0.6f).fillMaxHeight().imePadding(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End),
                            ).padding(vertical = 16.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBlock(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(BrandTheme.colors.header)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** "STEP 2 OF 3", the title and the line under it, in the header's colours. */
@Composable
internal fun StepTitle(
    step: String,
    title: String,
    intro: String?,
) {
    val brand = BrandTheme.colors
    Text(step.uppercase(), style = MaterialTheme.typography.labelMedium, color = brand.accent)
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        color = brand.onHeader,
        modifier = Modifier.semantics { heading() },
    )
    if (intro != null) Text(intro, style = MaterialTheme.typography.bodyLarge, color = brand.onHeaderMuted)
}

/** Space between a section label and its card, and the side margins the mockups use. */
internal val SectionLabelPadding = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)
internal val CardPadding = Modifier.padding(horizontal = 16.dp)

@Composable
internal fun VerticalGap() = Spacer(Modifier.padding(top = 4.dp))

/** The colour bar of a folder (mockups 02 and 03). Dark green would vanish on dark cards, so it lightens there. */
@Composable
internal fun folderColor(role: FolderRole): Color {
    val dark = BrandTheme.colors == DarkBrandColors
    return when (role) {
        FolderRole.PRIORITY -> Brand.DarkRed
        FolderRole.PEOPLE -> Brand.Cognac
        FolderRole.MONEY -> Brand.Gold
        FolderRole.WORK -> if (dark) Brand.Beige else Brand.DarkGreen
        FolderRole.ORDERS -> Color(0xFF8C7A55)
        FolderRole.REPORTS -> Color(0xFF6F7A66)
        FolderRole.CUSTOM -> Color(0xFF8A7A5A)
        FolderRole.DISCARDED, FolderRole.JUNK -> Brand.DarkRed
    }
}

/** The small rounded colour bar in front of a folder's name. Decorative. */
@Composable
internal fun FolderBar(
    role: FolderRole,
    height: Int,
) {
    Box(Modifier.size(width = 10.dp, height = height.dp).background(folderColor(role), RoundedCornerShape(5.dp)))
}

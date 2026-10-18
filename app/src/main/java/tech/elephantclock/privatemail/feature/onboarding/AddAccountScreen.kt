package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.feature.shell.rememberWindowInfo
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** What the screen can ask for; the view model implements it, screenshots pass [NoActions]. */
interface AddAccountActions {
    fun onEmailChange(email: String)

    fun onEmailDone()

    fun onPasswordChange(password: String)

    fun onUsernameChange(username: String)

    fun onImapChange(fields: ServerFields)

    fun onSmtpChange(fields: ServerFields)

    fun onToggleServerSettings()

    fun onConfirmHosts()

    fun onTestConnection()

    fun onAddAccount()

    fun onTrustCertificate()

    fun onRejectCertificate()

    object NoActions : AddAccountActions {
        override fun onEmailChange(email: String) = Unit

        override fun onEmailDone() = Unit

        override fun onPasswordChange(password: String) = Unit

        override fun onUsernameChange(username: String) = Unit

        override fun onImapChange(fields: ServerFields) = Unit

        override fun onSmtpChange(fields: ServerFields) = Unit

        override fun onToggleServerSettings() = Unit

        override fun onConfirmHosts() = Unit

        override fun onTestConnection() = Unit

        override fun onAddAccount() = Unit

        override fun onTrustCertificate() = Unit

        override fun onRejectCertificate() = Unit
    }
}

/** Screen 01 wired to its view model. [onAdded] runs once the account is saved. */
@Composable
fun AddAccountRoute(
    viewModel: AddAccountViewModel,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
    reentry: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    if (state.saved) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onAdded() }
    }
    AddAccountScreen(state, password, viewModel, modifier, reentry = reentry)
}

/**
 * Add an account (mockup 01): email and password first, settings found automatically, an
 * editable "Server settings" section, plain-language errors. One column on the cover screen;
 * on the inner screen the brand panel sits beside a form capped at 480 dp.
 */
@Composable
fun AddAccountScreen(
    state: AddAccountUiState,
    password: String,
    actions: AddAccountActions,
    modifier: Modifier = Modifier,
    windowInfo: WindowInfo = rememberWindowInfo(),
    reentry: Boolean = false,
) {
    // Surface sets the content colour, so text without an explicit colour reads in both themes.
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AddAccountLayout(state, password, actions, windowInfo, reentry)
    }
    state.certificate?.let { CertificateDialog(it, actions::onTrustCertificate, actions::onRejectCertificate) }
}

@Composable
private fun AddAccountLayout(
    state: AddAccountUiState,
    password: String,
    actions: AddAccountActions,
    windowInfo: WindowInfo,
    reentry: Boolean,
) {
    if (windowInfo.isCompactWidth) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            Hero(reentry, compact = true, Modifier.fillMaxWidth())
            AddAccountForm(state, password, actions, Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp))
            Footer(Modifier.padding(24.dp))
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Hero(reentry, compact = false, Modifier.weight(0.4f).fillMaxHeight())
            Box(Modifier.weight(0.6f).fillMaxHeight().imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End),
                        ).padding(horizontal = 32.dp, vertical = 32.dp),
                ) {
                    AddAccountForm(state, password, actions)
                    Footer(Modifier.padding(top = 32.dp))
                }
            }
        }
    }
}

@Composable
private fun Hero(
    reentry: Boolean,
    compact: Boolean,
    modifier: Modifier,
) {
    val brand = BrandTheme.colors
    Box(modifier.background(brand.header).clipToBounds()) {
        // Decorative gold disc from the mockup, top right.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 60.dp, y = (-40).dp)
                .size(220.dp)
                .background(brand.accent.copy(alpha = 0.12f), CircleShape),
        )
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                .padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ClockMark()
            Text(
                text =
                    if (reentry) {
                        buildAnnotatedString { append(stringResource(R.string.add_title_reenter)) }
                    } else {
                        buildAnnotatedString {
                            append(stringResource(R.string.add_title_keep))
                            append("\n")
                            withStyle(
                                SpanStyle(color = brand.accent),
                            ) { append(stringResource(R.string.add_title_discard)) }
                        }
                    },
                // The side panel on the inner screen is ~300 dp wide: one size down so lines don't break mid-phrase.
                style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                color = brand.onHeader,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(if (reentry) R.string.add_intro_reenter else R.string.add_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = brand.onHeaderMuted,
            )
        }
    }
}

/** The ElephantClock clock mark: a gold ring with two hands. Decorative. */
@Composable
private fun ClockMark() {
    val gold = BrandTheme.colors.accent
    Canvas(Modifier.size(48.dp)) {
        val stroke = 3.dp.toPx() * size.width / 52.dp.toPx()
        val unit = size.width / 52f
        drawCircle(gold, radius = 23f * unit, style = Stroke(stroke))
        drawLine(gold, Offset(26f * unit, 13f * unit), Offset(26f * unit, 26f * unit), stroke, StrokeCap.Round)
        drawLine(gold, Offset(26f * unit, 26f * unit), Offset(35f * unit, 32f * unit), stroke, StrokeCap.Round)
    }
}

@Composable
private fun Footer(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.add_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = BrandTheme.colors.accent)
        Text(
            stringResource(R.string.add_footer_brand),
            style = MaterialTheme.typography.labelMedium,
            color = BrandTheme.colors.sectionLabel,
        )
        Box(Modifier.height(8.dp))
    }
}

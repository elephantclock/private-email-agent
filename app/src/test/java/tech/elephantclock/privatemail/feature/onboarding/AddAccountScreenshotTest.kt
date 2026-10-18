package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.size
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoveredSettings
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoveryResult
import tech.elephantclock.privatemail.core.mail.autoconfig.ProviderNote
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureAllSizes
import tech.elephantclock.privatemail.ui.screenshot.captureScreen
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

/** Screen 01 in its main states. Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class AddAccountScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun found(settings: DiscoveredSettings) =
        AddAccountUiState(
            email = settings.username,
            discovery = DiscoveryStatus.Found,
            providerId = settings.providerId,
            providerName = settings.providerName,
            note = settings.note,
            passwordSupported = settings.passwordSupported,
            username = settings.username,
            imap = ServerFields.of(settings.imap),
            smtp = ServerFields.of(settings.smtp),
            confirmationPending = settings.needsConfirmation,
        )

    private val izi =
        AddAccountUiState(
            email = "nishad@izi.travel",
            discovery = DiscoveryStatus.Found,
            username = "nishad@izi.travel",
            imap = ServerFields("mail.izi.travel", "993", ConnectionSecurity.SSL_TLS),
            smtp = ServerFields("mail.izi.travel", "587", ConnectionSecurity.STARTTLS),
        )

    @Test
    fun empty() {
        captureAllSizes(
            "add_account_empty",
        ) { info -> AddAccountScreen(AddAccountUiState(), "", AddAccountActions.NoActions, windowInfo = info) }
    }

    @Test
    fun foundWithPassword() {
        captureAllSizes(
            "add_account_found",
        ) { info -> AddAccountScreen(izi, "secret-pass", AddAccountActions.NoActions, windowInfo = info) }
    }

    @Test
    fun appPasswordNote() {
        val gmail = found((GMAIL as DiscoveryResult.Found).settings)
        listOf(false, true).forEach { dark ->
            captureScreen(
                "add_account_gmail",
                ScreenSize.Cover,
                dark,
            ) { info -> AddAccountScreen(gmail, "", AddAccountActions.NoActions, windowInfo = info) }
        }
    }

    @Test
    fun microsoftWarning() {
        val ms =
            AddAccountUiState(
                email = "ann@outlook.com",
                discovery = DiscoveryStatus.Found,
                providerName = "Outlook.com / Microsoft 365",
                note = ProviderNote.MicrosoftPasswordBlocked("https://support.microsoft.com/"),
                passwordSupported = false,
                username = "ann@outlook.com",
                imap = ServerFields("outlook.office365.com", "993", ConnectionSecurity.SSL_TLS),
                smtp = ServerFields("smtp.office365.com", "587", ConnectionSecurity.STARTTLS),
            )
        captureScreen(
            "add_account_microsoft",
            ScreenSize.Cover,
            false,
        ) { info -> AddAccountScreen(ms, "", AddAccountActions.NoActions, windowInfo = info) }
    }

    @Test
    fun hostsNeedConfirmation() {
        val state = found((SRV_ELSEWHERE as DiscoveryResult.Found).settings)
        captureAllSizes(
            "add_account_confirm",
        ) { info -> AddAccountScreen(state, "", AddAccountActions.NoActions, windowInfo = info) }
    }

    @Test
    fun manualSettingsWithErrors() {
        val state =
            izi.copy(
                discovery = DiscoveryStatus.Manual,
                serverSettingsOpen = true,
                problems =
                    listOf(
                        ServerProblem(ServerRole.Incoming, AuthError.WrongCredentials.explain()),
                        ServerProblem(
                            ServerRole.Outgoing,
                            AuthError.AuthMethodNotSupported("535 5.7.8 Authentication credentials invalid").explain(),
                        ),
                    ),
            )
        listOf(false, true).forEach { dark ->
            captureScreen(
                "add_account_errors",
                ScreenSize.Cover,
                dark,
            ) { info -> AddAccountScreen(state, "secret-pass", AddAccountActions.NoActions, windowInfo = info) }
        }
        captureScreen("add_account_errors", ScreenSize.InnerLandscape, false) { info ->
            AddAccountScreen(state, "secret-pass", AddAccountActions.NoActions, windowInfo = info)
        }
    }

    /** A tall cover-width window, so the whole server settings section is in the picture. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun serverSettingsSection() {
        val state = izi.copy(serverSettingsOpen = true, testPassed = true)
        captureRoboImage(
            filePath = "src/test/screenshots/add_account_settings_tall_light.png",
            roborazziComposeOptions = RoborazziComposeOptions { size(widthDp = 412, heightDp = 1700) },
        ) {
            PrivateMailTheme(darkTheme = false) {
                AddAccountScreen(state, "secret-pass", AddAccountActions.NoActions, windowInfo = WindowInfo(412, 1700))
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h960dp-xhdpi")
    fun certificateDialog() {
        val state =
            izi.copy(
                certificate =
                    cert(
                        "mail.corp.example",
                        993,
                        sha =
                            (1..32).joinToString(":") {
                                "%02X".format(it * 7)
                            },
                    ),
            )
        compose.setContent {
            PrivateMailTheme(darkTheme = false) {
                AddAccountScreen(state, "secret-pass", AddAccountActions.NoActions, windowInfo = WindowInfo(412, 960))
            }
        }
        captureScreenRoboImage("src/test/screenshots/add_account_certificate_cover_light.png")
    }
}

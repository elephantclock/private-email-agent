package tech.elephantclock.privatemail.feature.rules

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.CustomFolder
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureAllSizes
import tech.elephantclock.privatemail.ui.screenshot.captureScreen
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

/** Screen 07 (Folders and rules). Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class RulesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rules() {
        captureAllSizes("rules") { RulesScreen(sample(), RulesActions.NoActions, {}, {}) }
    }

    /** The whole cover-width page at once, to check the sections below the fold. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun tallPage() {
        listOf(false, true).forEach { dark ->
            val theme = if (dark) "dark" else "light"
            captureRoboImage(
                filePath = "src/test/screenshots/rules_full_cover_$theme.png",
                roborazziComposeOptions = RoborazziComposeOptions { size(widthDp = 412, heightDp = 2400) },
            ) {
                PrivateMailTheme(darkTheme = dark) {
                    RulesScreen(
                        sample().copy(
                            showAdvanced = true,
                            gate = BackfillStatus.PENDING,
                            hasTrash = false,
                            prefs = sample().prefs.copy(discardMode = DiscardMode.STRAIGHT_TO_TRASH),
                        ),
                        RulesActions.NoActions,
                        {},
                        {},
                    )
                }
            }
        }
    }

    @Test
    fun dialogs() {
        val rule = sample().rules.first()
        captureScreen("rules_retarget", ScreenSize.Cover, dark = false) {
            RulesScreen(sample().copy(dialog = RulesDialog.Retarget(rule)), RulesActions.NoActions, {}, {})
        }
        captureScreen("rules_add_folder", ScreenSize.Cover, dark = true) {
            RulesScreen(
                sample().copy(
                    dialog =
                        RulesDialog.AddFolder(
                            "Work/Secret",
                            "",
                            problem = tech.elephantclock.privatemail.core.data.settings.FolderNameProblem.BAD_CHARACTER,
                        ),
                ),
                RulesActions.NoActions,
                {},
                {},
            )
        }
    }

    private companion object {
        fun sample() =
            RulesUiState(
                loading = false,
                accounts = listOf(RulesAccount(ACCOUNT_A, "izi.travel"), RulesAccount(ACCOUNT_B, "elephantclock")),
                selected = ACCOUNT_A,
                prefs =
                    AccountPrefs(
                        autoFileRequested = true,
                        disabledFolders = setOf(FolderRole.PEOPLE),
                        folderDescriptions =
                            mapOf(FolderRole.WORK to "Bitbucket, Jira, App Store Connect, build and review mail."),
                        customFolders =
                            listOf(
                                CustomFolder(
                                    "Customer support",
                                    "App users with payment or access problems, refunds. Any language.",
                                ),
                                CustomFolder(
                                    "Partners",
                                    "Anyone asking to integrate, resell or publish content. Not vendors pitching.",
                                ),
                            ),
                        alertHigh = true,
                        trustedAuthservIds = setOf("mx.izi.example"),
                    ),
                rules =
                    listOf(
                        RuleRow(
                            1,
                            "appfigures.com",
                            FolderTarget(FolderRole.REPORTS),
                            vip = false,
                            allAccounts = false,
                        ),
                        RuleRow(
                            2,
                            "noreply@registrations.example",
                            FolderTarget.Discarded,
                            vip = false,
                            allAccounts = true,
                        ),
                        RuleRow(3, "us@informap.example", FolderTarget.Priority, vip = true, allAccounts = false),
                    ),
                gate = BackfillStatus.COMPLETE,
            )
    }
}

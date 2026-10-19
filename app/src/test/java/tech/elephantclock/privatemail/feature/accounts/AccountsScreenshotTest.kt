package tech.elephantclock.privatemail.feature.accounts

import androidx.compose.runtime.Composable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureAllSizes
import tech.elephantclock.privatemail.ui.screenshot.captureScreen

/** The Accounts screen (T52). Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class AccountsScreenshotTest {
    private val state =
        AccountsUiState(
            loading = false,
            accounts =
                listOf(
                    AccountItem("w", "sam@work.example", "Work", 0, false, ReadingFixtures.NOW - 7 * 60_000),
                    AccountItem("h", "sam@home.example", "sam@home.example", 4, true, ReadingFixtures.NOW - 86_400_000),
                    AccountItem("o", "old.address@longprovider.example", "Old address", 2, false, null),
                ),
        )

    private fun screen(s: AccountsUiState) =
        @Composable { _: WindowInfo ->
            AccountsScreen(
                s,
                AccountsScreenActions.NoActions,
                onAddAccount = {},
                onSignInAgain = {},
                times = ReadingFixtures.times,
            )
        }

    @Test
    fun accounts() = captureAllSizes("accounts", screen(state))

    @Test
    fun removeConfirmation() =
        captureScreen(
            "accounts_confirm_remove",
            ScreenSize.Cover,
            dark = false,
            screen(state.copy(dialog = AccountsDialog.ConfirmRemove("h", "sam@home.example"))),
        )

    @Test
    fun renameAndRemoving() =
        captureScreen(
            "accounts_rename",
            ScreenSize.Cover,
            dark = true,
            screen(state.copy(dialog = AccountsDialog.Rename("h", "sam@home.example", "Home"), removing = "o")),
        )
}

package tech.elephantclock.privatemail.feature.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

/** Fold, unfold and rotation recreate the activity; the tab and the open item must come back. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w750dp-h832dp")
class AppShellStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun destinationAndOpenItemSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PrivateMailTheme {
                AppShell(windowInfo = WindowInfo(750, 832), listPane = sampleList, detailPane = sampleDetail)
            }
        }
        compose.onNodeWithText("Folders").performClick()
        compose.onNodeWithTag("item:Bitbucket").performClick()
        compose.onNodeWithTag("detail:Bitbucket").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Folders").assertIsSelected()
        compose.onNodeWithTag("detail:Bitbucket").assertExists()
    }

    @Test
    fun openItemSurvivesUnfoldingFromCoverToInnerScreen() {
        var info by mutableStateOf(WindowInfo(412, 960))
        compose.setContent {
            PrivateMailTheme { AppShell(windowInfo = info, listPane = sampleList, detailPane = sampleDetail) }
        }
        compose.onNodeWithTag("item:Informap").performClick()
        compose.onNodeWithTag("detail:Informap").assertExists()

        info = WindowInfo(750, 832)
        compose.waitForIdle()

        compose.onNodeWithTag("detail:Informap").assertExists()
        compose.onNodeWithTag("item:Informap").assertExists()
    }
}

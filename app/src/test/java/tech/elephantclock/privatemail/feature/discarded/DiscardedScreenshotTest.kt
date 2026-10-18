package tech.elephantclock.privatemail.feature.discarded

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureAllSizes
import tech.elephantclock.privatemail.ui.screenshot.captureScreen
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

/** Screen 05 (Discarded digest). Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class DiscardedScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bySender() {
        captureAllSizes("discarded_by_sender") { DiscardedScreen(sampleState(), DiscardedScreenActions.NoActions) }
    }

    @Test
    fun byReason() {
        val state = sampleState(Grouping.REASON)
        captureScreen("discarded_by_reason", ScreenSize.Cover, dark = false) {
            DiscardedScreen(state, DiscardedScreenActions.NoActions)
        }
    }

    @Test
    fun spamAndEmpty() {
        captureScreen("discarded_spam", ScreenSize.Cover, dark = true) {
            DiscardedScreen(sampleState(Grouping.SPAM), DiscardedScreenActions.NoActions)
        }
        captureScreen("discarded_empty", ScreenSize.Cover, dark = false) {
            DiscardedScreen(DiscardedUiState(loading = false), DiscardedScreenActions.NoActions)
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h960dp-xhdpi")
    fun mailtoConfirmation() {
        val state = sampleState()
        val message =
            OutgoingMessage(ME, "unsubscribe", "unsubscribe", to = listOf(Address.of("leave@seo-agency.example")))
        compose.setContent {
            PrivateMailTheme(darkTheme = false) {
                DiscardedScreen(
                    state.copy(confirm = UnsubscribePlan.Mail("k", "pitch@seo-agency.example", ACCOUNT, message)),
                    DiscardedScreenActions.NoActions,
                )
            }
        }
        captureScreenRoboImage("src/test/screenshots/discarded_confirm_mail_cover_light.png")
    }

    @Test
    @Config(qualifiers = "w412dp-h960dp-night-xhdpi")
    fun oneClickConfirmation() {
        val state = sampleState()
        compose.setContent {
            PrivateMailTheme(darkTheme = true) {
                DiscardedScreen(
                    state.copy(
                        confirm =
                            UnsubscribePlan.OneClick(
                                "k",
                                "news@travel-deals.example",
                                "https://lists.example/u",
                                "lists.example",
                            ),
                    ),
                    DiscardedScreenActions.NoActions,
                )
            }
        }
        captureScreenRoboImage("src/test/screenshots/discarded_confirm_one_click_cover_dark.png")
    }

    private companion object {
        val ACCOUNT = AccountId("a1")
        val ME = Address.of("alex@ourco.example")
        const val NOW = 1_800_000_000_000L
        val ONE_CLICK = UnsubscribeMethod.OneClick("https://lists.example/u")
        val MAILTO = UnsubscribeMethod.Mailto(Address.of("leave@seo-agency.example"), "unsubscribe", "unsubscribe")

        fun items(): List<DiscardedItem> {
            var id = 0L

            fun many(
                n: Int,
                email: String,
                name: String?,
                reason: String,
                unsubscribe: UnsubscribeMethod? = null,
                action: VerdictAction = VerdictAction.DISCARD,
            ) = (1..n).map {
                id++
                DiscardedItem(
                    id,
                    ACCOUNT,
                    ME,
                    Address.of(email, name),
                    "Subject $id",
                    NOW - id,
                    action,
                    reason,
                    NOW - id,
                    unsubscribe,
                )
            }
            return many(27, "noreply@signup.example", null, "Low-value notification · automated") +
                many(
                    9,
                    "news@travel-deals.example",
                    "Travel newsletter",
                    "Marketing · List-Unsubscribe header",
                    ONE_CLICK,
                ) +
                many(6, "pitch@seo-agency.example", "SEO agency pitch", "Cold sales · vendor pitching to you", MAILTO) +
                many(15, "digest@social.example", "Social network digests", "Low-value notification", ONE_CLICK) +
                many(
                    4,
                    "notice@paypa1.example",
                    "\"Paypa1\" account notice",
                    "Phishing · look-alike domain, DKIM fail",
                    ONE_CLICK,
                    VerdictAction.JUNK,
                )
        }

        fun sampleState(grouping: Grouping = Grouping.SENDER): DiscardedUiState {
            val all = items()
            return DiscardedUiState(
                loading = false,
                grouping = grouping,
                groups = DiscardGrouping.group(all, grouping),
                discardedToday = all.count { it.action == VerdictAction.DISCARD },
                spamCount = all.count { it.action == VerdictAction.JUNK },
            )
        }
    }
}

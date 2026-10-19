package tech.elephantclock.privatemail.feature.onboarding

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunReview
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.CustomFolder
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Stage
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Status
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureAllSizes
import tech.elephantclock.privatemail.ui.screenshot.captureScreen

/** Screens 02 and 03 in their main states. Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class OnboardingScreenshotTest {
    private val personalise =
        PersonaliseUiState(people = SAMPLE_PEOPLE, prefs = AccountPrefs(vips = setOf("irina@partner.example")))

    private val review = FirstRunReview.of(SAMPLE_COUNTS, AccountPrefs())
    private val ready =
        FirstRunUiState(
            work = FirstRunWorkState(Mode.SORT, Status.SUCCEEDED),
            review = review,
            backfill = BackfillStatus.COMPLETE,
        )

    @Test
    fun personalise() {
        captureAllSizes(
            "personalise",
        ) { info -> PersonaliseScreen(personalise, PersonaliseActions.NoActions, windowInfo = info) }
    }

    @Test
    fun personaliseReviewingPeople() {
        val state =
            personalise.copy(
                reviewingPeople = true,
                prefs =
                    personalise.prefs.copy(
                        disabledFolders = setOf(FolderRole.REPORTS),
                        customFolders =
                            listOf(
                                CustomFolder("Partners", "anyone asking to integrate or resell our product"),
                            ),
                        discardMode = DiscardMode.STRAIGHT_TO_TRASH,
                    ),
                customDraft = "Money",
                customProblem = CustomFolderProblem.DUPLICATE,
            )
        listOf(false, true).forEach { dark ->
            captureScreen("personalise_people", ScreenSize.Cover, dark) { info ->
                PersonaliseScreen(state, PersonaliseActions.NoActions, windowInfo = info)
            }
        }
        captureScreen("personalise_people", ScreenSize.InnerLandscape, false) { info ->
            PersonaliseScreen(state, PersonaliseActions.NoActions, windowInfo = info)
        }
    }

    @Test
    fun personaliseReadingSent() {
        val state = PersonaliseUiState(work = FirstRunWorkState(Mode.PREPARE, Status.RUNNING, Stage.SENT, 120, 900))
        captureScreen("personalise_reading", ScreenSize.Cover, false) { info ->
            PersonaliseScreen(state, PersonaliseActions.NoActions, windowInfo = info)
        }
    }

    @Test
    fun firstRunReview() {
        captureAllSizes("first_run") { info -> FirstRunScreen(ready, FirstRunActions.NoActions, windowInfo = info) }
    }

    @Test
    fun firstRunSorting() {
        val state = FirstRunUiState(work = sorting(640, 2060))
        listOf(ScreenSize.Cover, ScreenSize.InnerLandscape).forEach { size ->
            listOf(false, true).forEach { dark ->
                captureScreen(
                    "first_run_sorting",
                    size,
                    dark,
                ) { info -> FirstRunScreen(state, FirstRunActions.NoActions, windowInfo = info) }
            }
        }
    }

    @Test
    fun firstRunDrillDown() {
        val state = ready.copy(drillDown = review.discard.first(), items = SAMPLE_ITEMS)
        listOf(false, true).forEach { dark ->
            captureScreen("first_run_drilldown", ScreenSize.Cover, dark) { info ->
                FirstRunScreen(state, FirstRunActions.NoActions, windowInfo = info)
            }
        }
        captureScreen("first_run_drilldown", ScreenSize.InnerPortrait, false) { info ->
            FirstRunScreen(state, FirstRunActions.NoActions, windowInfo = info)
        }
    }

    @Test
    fun firstRunGateClosed() {
        captureScreen("first_run_gate", ScreenSize.Cover, false) { info ->
            FirstRunScreen(ready.copy(backfill = BackfillStatus.PENDING), FirstRunActions.NoActions, windowInfo = info)
        }
        captureScreen("first_run_failed", ScreenSize.Cover, false) { info ->
            FirstRunScreen(
                FirstRunUiState(work = FirstRunWorkState(Mode.SORT, Status.FAILED)),
                FirstRunActions.NoActions,
                windowInfo = info,
            )
        }
    }
}

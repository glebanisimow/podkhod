package app.podkhod

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Stage 2: the generated program, its explanation, profile edits with a diff, and the plate calculator. */
@RunWith(AndroidJUnit4::class)
class ProgramFlowTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(DisableAnimationsRule()).around(NoActiveWorkoutRule()).around(compose)

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun generatedProgramIsExplained() {
        compose.reachToday()
        compose.onNodeWithTag("nav_Program").performClick()
        compose.waitFor("week_strip")
        compose.onNodeWithTag("program_why").performClick()
        compose.waitFor("screen_why")
        compose.onNodeWithTag("why_volume").assertIsDisplayed()
    }

    @Test
    fun profileEditShowsTheDiffAndRebuildsTheProgram() {
        compose.reachToday()
        compose.onNodeWithTag("nav_Program").performClick()
        compose.waitFor("program_day_0")
        // Alternate between 3 and 4 days so the edit always changes the program, whatever the previous run left.
        val hadFourDays = compose.exists("program_day_3")
        val days = if (hadFourDays) 3 else 4

        compose.onNodeWithTag("nav_Settings").performClick()
        compose.onNodeWithTag("settings_profile").performScrollTo().performClick()
        compose.waitFor("screen_profile")
        compose.onNodeWithTag("profile_step_schedule").performClick()
        compose.onNodeWithTag("days_$days").performClick()
        compose.onNodeWithTag("profile_save").performClick()
        compose.waitFor("profile_diff")
        compose.onNodeWithTag("profile_diff_apply").performClick()
        compose.waitUntil(15_000) { !compose.exists("screen_profile") }

        compose.onNodeWithTag("nav_Program").performClick()
        compose.waitFor("program_day_0")
        compose.waitUntil(10_000) { compose.exists("program_day_3") == (days == 4) }
        assertEquals(days == 4, compose.exists("program_day_3"))
    }

    @Test
    fun plateCalculatorOffersNearestLoads() {
        compose.reachToday()
        compose.onNodeWithTag("nav_Settings").performClick()
        compose.onNodeWithTag("settings_plate_calc").performScrollTo().performClick()
        compose.waitFor("screen_plate_calc")
        compose.onNodeWithTag("plate_calc_target").performTextInput("101")
        compose.waitFor("plate_solution")
        compose.onNodeWithTag("plate_solution").assertIsDisplayed()
        back()
    }
}

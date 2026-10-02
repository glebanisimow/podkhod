package app.podkhod

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** The stage 1 acceptance path: start workout → log a set → replace an exercise → finish. */
@RunWith(AndroidJUnit4::class)
class WorkoutScenarioTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(DisableAnimationsRule())
        .around(NoActiveWorkoutRule())
        .around(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                GrantPermissionRule.grant()
            },
        )
        .around(compose)

    private fun exists(tag: String) = compose.exists(tag)

    private fun waitFor(tag: String, timeoutMs: Long = 15_000) = compose.waitFor(tag, timeoutMs)

    @Test
    fun startLogReplaceFinish() {
        compose.openWorkout(checkin = true)
        compose.onNodeWithTag("workout_scheme_0").assertIsDisplayed()

        // Logging a prefilled set is a single tap.
        compose.onNodeWithTag("workout_log_set").performClick()
        waitFor("rest_bar")
        compose.onNodeWithTag("rest_left").assertIsDisplayed()
        compose.onNodeWithTag("rest_plus").performClick()
        compose.onNodeWithTag("rest_skip").performClick()
        compose.waitUntil(5_000) { !exists("rest_bar") }

        // Busy station: "Занято" + first option is the two-tap replacement.
        val lastChip = goToLastSlot()
        compose.waitUntil(5_000) { title(lastChip) != null }
        val before = title(lastChip)
        compose.onNodeWithTag("workout_busy").performClick()
        waitFor("busy_option_0")
        compose.onNodeWithTag("busy_option_0").performClick()
        compose.waitUntil(5_000) { !exists("busy_sheet") }
        compose.waitUntil(5_000) { title(lastChip) != before }
        assertNotEquals(before, title(lastChip))

        compose.onNodeWithTag("workout_finish").performClick()
        compose.onNodeWithTag("finish_confirm").performClick()
        waitFor("screen_summary")
        compose.onNodeWithTag("summary_totals").assertIsDisplayed()
        compose.onNodeWithTag("feeling_2").performClick()
        compose.onNodeWithTag("summary_done").performClick()
        waitFor("screen_today")
        waitFor("today_start")
    }

    @Test
    fun busyLaterMovesExerciseToTheEnd() {
        compose.openWorkout()
        waitFor("slot_chip_0")
        compose.onNodeWithTag("slot_chip_0").performClick()
        compose.waitUntil(5_000) { title(0) != null }
        val first = title(0)
        compose.onNodeWithTag("workout_busy").performClick()
        waitFor("busy_later")
        compose.onNodeWithTag("busy_later").performClick()
        compose.waitUntil(5_000) { !exists("busy_sheet") }
        compose.waitUntil(5_000) { title(0) != null && title(0) != first }
        val last = goToLastSlot()
        compose.waitUntil(5_000) { listOf(last - 1, last).any { title(it) == first } }

        compose.onNodeWithTag("workout_finish").performClick()
        compose.onNodeWithTag("finish_confirm").performClick()
        waitFor("screen_summary")
        compose.onNodeWithTag("summary_done").performClick()
        waitFor("screen_today")
    }

    private companion object {
        const val MAX_CHIP_STEPS = 8
    }

    /** The chip strip is lazy: taps the last composed chip until no chip with a higher index appears. */
    private fun goToLastSlot(): Int {
        var last = maxChipIndex()
        for (step in 0 until MAX_CHIP_STEPS) {
            compose.onNodeWithTag("slot_chip_$last").performClick()
            compose.waitForIdle()
            val now = maxChipIndex()
            if (now == last) break
            last = now
        }
        return last
    }

    private fun maxChipIndex(): Int = compose.onAllNodes(SemanticsMatcher("slot chip") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("slot_chip_") == true
    }).fetchSemanticsNodes().maxOf { it.config[SemanticsProperties.TestTag].removePrefix("slot_chip_").toInt() }

    private fun title(index: Int): String? =
        compose.onAllNodesWithTag("exercise_title_$index").fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
}

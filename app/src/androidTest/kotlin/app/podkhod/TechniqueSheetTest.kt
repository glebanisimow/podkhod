package app.podkhod

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TechniqueSheetTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(DisableAnimationsRule()).around(NoActiveWorkoutRule()).around(compose)

    @Test
    fun tappingAnExerciseOnTodayOpensItsTechnique() {
        compose.reachToday()
        compose.waitFor("today_next")
        val row = SemanticsMatcher("today exercise row") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("today_exercise_") == true
        }
        compose.onAllNodes(row).onFirst().performScrollTo().performClick()
        compose.waitFor("technique_sheet")
        compose.onNodeWithTag("exercise_media").assertIsDisplayed()
    }

    @Test
    fun programSlotSheetOpensTechnique() {
        compose.reachToday()
        compose.onNodeWithTag("nav_Program").performClick()
        compose.waitFor("screen_program")
        val row = SemanticsMatcher("planned row") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("planned_") == true
        }
        compose.onAllNodes(row).onFirst().performScrollTo().performClick()
        compose.waitFor("slot_technique")
        compose.onNodeWithTag("slot_technique").performScrollTo().performClick()
        compose.waitFor("technique_sheet")
        compose.onNodeWithTag("exercise_media").assertIsDisplayed()
    }
}

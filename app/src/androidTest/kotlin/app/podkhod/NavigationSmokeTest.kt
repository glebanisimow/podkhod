package app.podkhod

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(DisableAnimationsRule()).around(compose)

    @Test
    fun bottomBarReachesEveryTopLevelScreenAndAbout() {
        compose.reachToday()
        compose.onNodeWithTag("screen_today").assertIsDisplayed()

        compose.onNodeWithTag("nav_Program").performClick()
        compose.onNodeWithTag("screen_program").assertIsDisplayed()

        compose.onNodeWithTag("nav_History").performClick()
        compose.onNodeWithTag("screen_history").assertIsDisplayed()

        compose.onNodeWithTag("nav_Settings").performClick()
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithTag("about_version").assertIsDisplayed()

        compose.onNodeWithTag("about_media_licenses").performScrollTo().performClick()
        compose.onNodeWithTag("screen_licenses").assertIsDisplayed()
    }
}

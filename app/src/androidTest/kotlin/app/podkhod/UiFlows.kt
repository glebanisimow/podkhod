package app.podkhod

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

fun ComposeTestRule.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.waitFor(tag: String, timeoutMs: Long = 15_000) = waitUntil(timeoutMs) { exists(tag) }

/** A fresh install opens onboarding; "Пропустить всё" builds the default program and lands on Today. */
fun ComposeTestRule.reachToday() {
    waitUntil(20_000) { exists("screen_today") || exists("screen_onboarding") }
    if (exists("screen_onboarding")) {
        onNodeWithTag("onboarding_skip_all").performClick()
        waitFor("screen_today", 20_000)
    }
}

/** Opens the workout: resumes an active one or starts the next day through the check-in dialog. */
fun ComposeTestRule.openWorkout(checkin: Boolean = false) {
    reachToday()
    waitUntil(20_000) { exists("today_start") || exists("today_continue") || exists("resume_dialog") }
    when {
        exists("resume_dialog") -> onNodeWithTag("resume_continue").performClick()
        exists("today_continue") -> onNodeWithTag("today_continue").performClick()
        else -> {
            onNodeWithTag("today_start").performClick()
            waitFor("checkin_dialog")
            onNodeWithTag(if (checkin) "checkin_start" else "checkin_skip").performClick()
        }
    }
    waitFor("screen_workout")
}

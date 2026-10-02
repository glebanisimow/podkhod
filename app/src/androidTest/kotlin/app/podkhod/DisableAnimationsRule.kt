package app.podkhod

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Turns system animations off for the test and restores them afterwards. The app then shows exercise
 * media as still frames: in the v2 Compose test runner composition does not run on the main thread, and
 * animated drawables invalidating from the main thread race with it.
 */
class DisableAnimationsRule : ExternalResource() {
    private val keys = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
    private val saved = mutableMapOf<String, String>()

    override fun before() {
        keys.forEach { key ->
            saved[key] = shell("settings get global $key").trim().takeIf { it.isNotEmpty() && it != "null" } ?: "1"
            shell("settings put global $key 0")
        }
    }

    override fun after() {
        saved.forEach { (key, value) -> shell("settings put global $key $value") }
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

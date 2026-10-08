package app.podkhod

import app.podkhod.data.db.WorkoutSessionEntity
import app.podkhod.data.settings.AppSettings
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.platform.WorkoutTracker
import app.podkhod.platform.nowMillis
import app.podkhod.resources.Res
import app.podkhod.resources.notif_rest_over_text
import app.podkhod.resources.notif_rest_over_title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * The end-of-rest alert on iPhone. iOS does not keep an app running in the background the way the Android
 * foreground service does, so the alert is a local notification scheduled for rest_ends_at: the system
 * delivers it with the screen locked, the app in the background or even after iOS has unloaded the app.
 * Every change of the rest (new set, skip, workout finished) replaces or removes the pending request.
 */
class IosWorkoutTracker(
    private val workouts: WorkoutRepository,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope,
) : WorkoutTracker {

    private var started = false

    override fun start() {
        if (started) return
        started = true
        center.delegate = ForegroundAlerts
        scope.launch {
            combine(workouts.activeSession, settingsRepo.settings) { session, settings -> plan(session, settings) }
                .distinctUntilChanged()
                .collectLatest { schedule(it) }
        }
    }

    private fun plan(session: WorkoutSessionEntity?, settings: AppSettings): RestAlert? {
        val endsAt = session?.restEndsAt ?: return null
        if (!settings.restSound && !settings.restVibration) return null
        return RestAlert(endsAt, settings.restSound, settings.restVibration)
    }

    private suspend fun schedule(alert: RestAlert?) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(REQUEST_ID))
        val seconds = alert?.let { (it.endsAt - nowMillis()) / 1000.0 } ?: return
        if (seconds < MIN_DELAY_S) return
        val content = UNMutableNotificationContent().apply {
            setTitle(getString(Res.string.notif_rest_over_title))
            setBody(getString(Res.string.notif_rest_over_text))
            // iOS has no separate vibration switch for a notification: with sound it buzzes by the system
            // haptics settings; a silent one only shows the banner.
            if (alert.sound) setSound(UNNotificationSound.defaultSound)
            setUserInfo(mapOf(KEY_VIBRATE to alert.vibration))
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(REQUEST_ID, content, trigger), null)
    }

    private data class RestAlert(val endsAt: Long, val sound: Boolean, val vibration: Boolean)

    /** iOS hides notifications of the app in the foreground unless asked; the rest alert should still ring. */
    private object ForegroundAlerts : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            val content = willPresentNotification.request.content
            if (content.userInfo[KEY_VIBRATE] == true) AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            val sound = if (content.sound != null) UNNotificationPresentationOptionSound else 0uL
            withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or sound)
        }
    }

    private companion object {
        const val REQUEST_ID = "rest_over"
        const val KEY_VIBRATE = "vibrate"

        /** Rests ending within a second are left to the open workout screen. */
        const val MIN_DELAY_S = 1.0

        val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()
    }
}

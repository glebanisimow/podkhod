package app.podkhod.platform

import kotlin.time.Clock

/** Shown on "О приложении" and written into backups. */
data class AppInfo(val versionName: String, val build: String)

/**
 * Beeps and buzzes of the timed-set countdown, played directly while the workout screen is up: the hold
 * is short and the phone is usually on the floor next to the lifter, so a notification would be late.
 * Sound and vibration follow the rest-signal settings.
 */
interface HoldSignals {
    /** Hold starts, or one of the last seconds ticks. */
    fun tick(sound: Boolean, vibration: Boolean)

    /** The planned time is up. */
    fun finish(sound: Boolean, vibration: Boolean)
}

/**
 * Keeps an active workout and its rest timer alive while the app is in the background (a foreground
 * service on Android). Safe to call repeatedly.
 */
fun interface WorkoutTracker {
    fun start()
}

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

object Log {
    fun w(tag: String, message: String, error: Throwable? = null) = platformLog(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = platformLog(LogLevel.ERROR, tag, message, error)
}

enum class LogLevel { WARN, ERROR }

internal expect fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?)

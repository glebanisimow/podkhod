package app.podkhod.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Beeps and buzzes of the timed-set countdown, played directly while the workout screen is up: the hold
 * is short and the phone is usually on the floor next to the lifter, so a notification would be late.
 * Sound and vibration follow the rest-signal settings.
 */
class HoldSignals(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ALARM, VOLUME)
    } catch (e: RuntimeException) {
        // Some devices refuse a tone generator (audio service busy); the countdown still shows on screen.
        Log.w("HoldSignals", "no tone generator", e)
        null
    }

    /** Hold starts, or one of the last seconds ticks. */
    fun tick(sound: Boolean, vibration: Boolean) {
        if (sound) tones?.startTone(ToneGenerator.TONE_PROP_BEEP, SHORT_MS)
        if (vibration) vibrate(SHORT_MS.toLong())
    }

    /** The planned time is up. */
    fun finish(sound: Boolean, vibration: Boolean) {
        if (sound) tones?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, LONG_MS)
        if (vibration) vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400), -1))
    }

    private fun vibrate(ms: Long) {
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val VOLUME = 100
        const val SHORT_MS = 150
        const val LONG_MS = 900
    }
}

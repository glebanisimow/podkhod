package app.podkhod

import app.podkhod.platform.HoldSignals
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate

/** System sounds: a short tick and the end-of-hold alert; the vibration follows the ring switch rules of iOS. */
class IosHoldSignals : HoldSignals {
    override fun tick(sound: Boolean, vibration: Boolean) {
        if (sound) AudioServicesPlaySystemSound(TICK)
        if (vibration) AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }

    override fun finish(sound: Boolean, vibration: Boolean) {
        if (sound) AudioServicesPlaySystemSound(ALERT)
        if (vibration) AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }

    private companion object {
        // Built-in system sound ids: "Tock" and "Alarm".
        const val TICK: UInt = 1104u
        const val ALERT: UInt = 1005u
    }
}

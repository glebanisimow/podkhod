package app.podkhod.domain.workout

/**
 * A timed set (plank, hang): a short get-ready countdown, then the hold itself counting down to the
 * planned seconds. Pure timestamps, so the screen can redraw from it at any moment and rotation or a
 * locked screen do not lose time.
 */
data class HoldTimer(
    val slotId: Long,
    val targetSec: Int,
    val startedAt: Long,
    val prepSec: Int = PREP_SEC,
) {
    val holdStartsAt: Long get() = startedAt + prepSec * 1000L
    val endsAt: Long get() = holdStartsAt + targetSec * 1000L

    fun phase(now: Long): Phase = when {
        now < holdStartsAt -> Phase.Prep(ceilSec(holdStartsAt - now))
        now < endsAt -> Phase.Hold(leftSec = ceilSec(endsAt - now), progress = (now - holdStartsAt).toFloat() / (endsAt - holdStartsAt))
        else -> Phase.Done
    }

    /** Seconds actually held when stopped at [now]; null while still getting ready (nothing to log). */
    fun heldSec(now: Long): Int? = when {
        now < holdStartsAt -> null
        else -> ((minOf(now, endsAt) - holdStartsAt) / 1000L).toInt()
    }

    sealed interface Phase {
        data class Prep(val leftSec: Int) : Phase
        data class Hold(val leftSec: Int, val progress: Float) : Phase
        data object Done : Phase
    }

    companion object {
        /** Time to get into position after the tap. */
        const val PREP_SEC = 5
        /** Short ticks before the end, so the last seconds can be counted without looking. */
        const val FINAL_TICKS = 3

        private fun ceilSec(ms: Long): Int = ((ms + 999) / 1000).toInt()
    }
}

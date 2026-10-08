package app.podkhod.domain.workout

/** Snapshot of one exercise slot inside an active session, as needed by [WorkoutFlow]. */
data class SlotState(
    val position: Int,
    val supersetGroup: String?,
    val plannedSets: Int,
    val loggedWorkSets: Int,
    val restSec: Int,
) {
    val remaining: Int get() = (plannedSets - loggedWorkSets).coerceAtLeast(0)
    val isDone: Boolean get() = remaining == 0
}

enum class SupersetRestMode { AFTER_ROUND, AFTER_EACH_EXERCISE }

data class AfterSetDecision(
    /** Position the screen should move to next. */
    val nextPosition: Int,
    /** Rest to start right now; 0 means no timer. */
    val restSec: Int,
)

/**
 * Decides where the workout goes after a set is logged.
 *
 * Superset members alternate (A1 → A2 → rest → A1 …). Rest starts after the last member of a round,
 * or after every member in [SupersetRestMode.AFTER_EACH_EXERCISE]. A finished exercise (or a finished
 * superset) never moves the screen on by itself: the lifter may add a set or check that the next machine is
 * free first, and goes on with [nextUnfinished]. Rest always starts after a work set. Warm-up sets get a
 * short fixed rest and never move the screen.
 */
object WorkoutFlow {
    const val WARMUP_REST_SEC = 60

    fun afterSet(
        slots: List<SlotState>,
        loggedPosition: Int,
        isWarmup: Boolean,
        mode: SupersetRestMode = SupersetRestMode.AFTER_ROUND,
    ): AfterSetDecision {
        val ordered = slots.sortedBy { it.position }
        val current = ordered.firstOrNull { it.position == loggedPosition }
            ?: return AfterSetDecision(loggedPosition, 0)

        if (isWarmup) return AfterSetDecision(loggedPosition, minOf(WARMUP_REST_SEC, current.restSec))

        val group = current.supersetGroup
            ?.let { g -> ordered.filter { it.supersetGroup == g } }
            ?.takeIf { it.size > 1 }

        if (group == null) return AfterSetDecision(current.position, current.restSec)

        val after = group.filter { it.position > current.position && !it.isDone }
        if (after.isNotEmpty()) {
            val rest = if (mode == SupersetRestMode.AFTER_EACH_EXERCISE) current.restSec else 0
            return AfterSetDecision(after.first().position, rest)
        }
        val nextRound = group.filter { !it.isDone }
        if (nextRound.isNotEmpty()) {
            // Members that already finished their sets no longer set the pace of the next round.
            return AfterSetDecision(nextRound.first().position, nextRound.maxOf { it.restSec })
        }

        return AfterSetDecision(current.position, group.maxOf { it.restSec })
    }

    /** Where «Дальше» leads from [position]: the next unfinished slot, wrapping to a skipped earlier one. */
    fun nextUnfinished(slots: List<SlotState>, position: Int): Int? {
        val ordered = slots.sortedBy { it.position }.filter { !it.isDone && it.position != position }
        return (ordered.firstOrNull { it.position > position } ?: ordered.firstOrNull())?.position
    }

    /** New end of rest after a ±delta tap; never earlier than now. */
    fun adjustRestEnd(endsAtMillis: Long, deltaSec: Int, nowMillis: Long): Long =
        maxOf(nowMillis, endsAtMillis + deltaSec * 1000L)
}

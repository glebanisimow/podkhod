package app.podkhod.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutFlowTest {

    private fun slot(pos: Int, group: String? = null, planned: Int = 3, logged: Int = 0, rest: Int = 90) =
        SlotState(pos, group, planned, logged, rest)

    @Test
    fun plainExerciseStaysUntilPlannedSetsAreDone() {
        val slots = listOf(slot(0, logged = 1, rest = 120), slot(1))
        assertEquals(AfterSetDecision(0, 120), WorkoutFlow.afterSet(slots, 0, isWarmup = false))
    }

    @Test
    fun plainExerciseMovesOnAfterLastSetAndStillRests() {
        val slots = listOf(slot(0, logged = 3, rest = 120), slot(1))
        assertEquals(AfterSetDecision(1, 120), WorkoutFlow.afterSet(slots, 0, isWarmup = false))
    }

    @Test
    fun supersetAlternatesWithoutRestThenRestsAfterRound() {
        val afterA1 = listOf(slot(0), slot(1, "A", logged = 1, rest = 90), slot(2, "A", logged = 0, rest = 60), slot(3))
        assertEquals(AfterSetDecision(2, 0), WorkoutFlow.afterSet(afterA1, 1, isWarmup = false))

        val afterA2 = listOf(slot(0), slot(1, "A", logged = 1, rest = 90), slot(2, "A", logged = 1, rest = 60), slot(3))
        assertEquals(AfterSetDecision(1, 90), WorkoutFlow.afterSet(afterA2, 2, isWarmup = false))
    }

    @Test
    fun supersetRestAfterEachExerciseWhenConfigured() {
        val afterA1 = listOf(slot(1, "A", logged = 1, rest = 90), slot(2, "A", logged = 0, rest = 60))
        assertEquals(
            AfterSetDecision(2, 90),
            WorkoutFlow.afterSet(afterA1, 1, isWarmup = false, mode = SupersetRestMode.AFTER_EACH_EXERCISE),
        )
    }

    @Test
    fun finishedSupersetMovesToNextUnfinishedSlot() {
        val slots = listOf(slot(0, logged = 3), slot(1, "A", logged = 3), slot(2, "A", logged = 3, rest = 75), slot(3))
        assertEquals(AfterSetDecision(3, 90), WorkoutFlow.afterSet(slots, 2, isWarmup = false))
    }

    @Test
    fun unevenSupersetKeepsOnlyUnfinishedMember() {
        val slots = listOf(slot(0, "B", planned = 2, logged = 2), slot(1, "B", planned = 3, logged = 2, rest = 45))
        assertEquals(AfterSetDecision(1, 45), WorkoutFlow.afterSet(slots, 1, isWarmup = false))
    }

    @Test
    fun skippedEarlierExerciseIsPickedUpAtTheEnd() {
        val slots = listOf(slot(0, logged = 0), slot(1, logged = 3), slot(2, logged = 3))
        assertEquals(0, WorkoutFlow.afterSet(slots, 2, isWarmup = false).nextPosition)
    }

    @Test
    fun warmupGetsShortRestAndKeepsPosition() {
        val slots = listOf(slot(0, logged = 0, rest = 150))
        assertEquals(AfterSetDecision(0, WorkoutFlow.WARMUP_REST_SEC), WorkoutFlow.afterSet(slots, 0, isWarmup = true))
    }

    @Test
    fun restAdjustmentNeverGoesIntoThePast() {
        val now = 1_000_000L
        assertEquals(now + 15_000, WorkoutFlow.adjustRestEnd(now, 15, now))
        assertEquals(now, WorkoutFlow.adjustRestEnd(now + 5_000, -15, now))
    }
}

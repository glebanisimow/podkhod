package app.podkhod.domain.plan

import app.podkhod.domain.model.Muscle
import app.podkhod.domain.workout.SupersetRestMode
import org.junit.Assert.*
import org.junit.Test

class AdaptivePlannerTest {
    private val config = bundledConfig.withMinSets(3)
    private val profile = Profile(sessionMinutes = 60)
    private val catalog = listOf(exercise("bench"), exercise("row", primary = setOf(Muscle.LATS))).associateBy { it.id }
    private val baseline = List(3) { PlannedDay("Day $it", listOf(slot("bench")), 15) }
    private val planner = AdaptivePlanner(config)

    private fun session(id: Long = 1, week: Int = 0, day: Int = 0, actual: Int = 1, hard: Boolean = false, deload: Boolean = false) =
        CompletedWorkout(id, id * 86_400_000, week, day, listOf(CompletedWork("bench", 3, actual)), hard, deload)

    private fun adapt(history: List<CompletedWorkout>, days: List<PlannedDay> = baseline, week: Int = 0, next: Int = 1, deload: Boolean = false, minutes: Int = 60) =
        planner.adapt(days, profile.copy(sessionMinutes = minutes), catalog, history, week, next, deload)

    @Test fun smallDeficitIsFilledWithinRemainingDaysOnly() {
        val plan = adapt(listOf(session()))
        assertEquals(3, plan.days[0].slots.single().sets)
        assertEquals(7, plan.days.drop(1).sumOf { it.slots.single().sets })
        assertEquals(8.0, plan.forecast.first { it.group == MuscleGroup.CHEST }.sets, 0.0)
        assertEquals(1, plan.historyCount)
    }

    @Test fun replayIsDeterministicAndDoesNotMutateBaseline() {
        val history = listOf(session())
        val first = adapt(history)
        repeat(20) { assertEquals(first, adapt(history)) }
        assertTrue(baseline.all { it.slots.single().sets == 3 })
    }

    @Test fun actualReplacementsAreCountedInsteadOfScheduledExercises() {
        val replaced = session().copy(work = listOf(CompletedWork("row", 3, 3)))
        val plan = adapt(listOf(replaced), days = baseline.map { it.copy(slots = it.slots.map { s -> s.copy(locked = true) }) })
        assertEquals(3.0, plan.forecast.first { it.group == MuscleGroup.BACK }.sets, 0.0)
        assertEquals(6.0, plan.forecast.first { it.group == MuscleGroup.CHEST }.sets, 0.0)
    }

    @Test fun emptyAndRecoverySessionsDoNotCreateCatchUpDebt() {
        listOf(session(actual = 0), session(hard = true), session(deload = true), session().copy(recoveryAdjusted = true)).forEach { s ->
            assertTrue(adapt(listOf(s)).days.all { it.slots.single().sets == 3 })
        }
        assertTrue(adapt(listOf(session()), week = 1, next = 0).days.all { it.slots.single().sets == 3 })
    }

    @Test fun twoDifficultExposuresEaseVolumeButOneDoesNot() {
        val history = listOf(session(actual = 2), session(id = 2, day = 1, actual = 2))
        assertTrue(adapt(history.take(1), week = 1, next = 0).days.all { it.slots.single().sets == 3 })
        assertTrue(adapt(history, week = 1, next = 0).days.all { it.slots.single().sets == 2 })
        assertTrue(adapt(history, week = 1, next = 0, deload = true).days.all { it.slots.single().sets == 3 })
        val stale = planner.adapt(baseline, profile, catalog, history, 1, 0, asOfMillis = 90L * 86_400_000)
        assertTrue(stale.days.all { it.slots.single().sets == 3 })
    }

    @Test fun recoveryCutsDoNotAccumulateWhenLowerPrescriptionIsCompleted() {
        val hard = listOf(session(actual = 2), session(id = 2, day = 1, actual = 2))
        val eased = session(id = 3, week = 1, actual = 2).copy(work = listOf(CompletedWork("bench", 2, 2)))
        assertTrue(adapt(hard + eased, week = 1, next = 1).days.all { it.slots.single().sets == 3 })
    }

    @Test fun locksAndManualSetCountsSurviveRecoveryAndDeficits() {
        val days = baseline.mapIndexed { index, day ->
            day.copy(slots = listOf(day.slots.single().copy(
                locked = index != 1,
                reasons = if (index == 1) listOf("Подходы изменены тобой: 2 → 3.") else emptyList(),
            )))
        }
        assertTrue(adapt(listOf(session()), days).days.all { it.slots.single().sets == 3 })
        assertTrue(adapt(listOf(session(), session(id = 2)), days, week = 1, next = 0).days.all { it.slots.single().sets == 3 })
    }

    @Test fun timeBudgetPreventsMakeUpSets() {
        assertTrue(adapt(listOf(session()), minutes = 10).days.all { it.slots.single().sets == 3 })
    }

    @Test fun additionalActualWorkReducesRemainingSurplusWithoutEditingCompletedDays() {
        val days = baseline.map { it.copy(slots = listOf(slot("bench", sets = 5))) }
        val plan = adapt(listOf(session(actual = 8)), days)
        assertEquals(listOf(5, 4, 4), plan.days.map { it.slots.single().sets })
        // The remaining surplus must remain visible instead of being hidden by a capped counter.
        assertEquals(16.0, plan.forecast.first { it.group == MuscleGroup.CHEST }.sets, 0.0)
    }

    @Test fun laterCompletedDayIsNotCountedAgainInForecast() {
        val plan = adapt(listOf(session(day = 2, actual = 3)), next = 1)
        assertEquals(3, plan.days[2].slots.single().sets)
        assertTrue(plan.forecast.first { it.group == MuscleGroup.CHEST }.sets <= 7.0)
    }

    @Test fun everyExerciseRestAndUnequalSupersetLengthsHaveCorrectTiming() {
        val a = slot("bench", sets = 3).copy(supersetGroup = "A", restSec = 60)
        val b = slot("row", sets = 2).copy(supersetGroup = "A", restSec = 90)
        val round = Workload(config, profile, catalog, restMode = SupersetRestMode.AFTER_ROUND)
        val each = Workload(config, profile, catalog, restMode = SupersetRestMode.AFTER_EACH_EXERCISE)
        assertEquals(2.0, each.minutes(listOf(a, b)) - round.minutes(listOf(a, b)), 1e-9)
        assertEquals(round.minutes(listOf(a.copy(supersetGroup = null))), round.minutes(listOf(a)), 1e-9)
    }
}

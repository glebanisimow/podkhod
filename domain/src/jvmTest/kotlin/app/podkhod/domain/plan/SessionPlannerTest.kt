package app.podkhod.domain.plan

import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import app.podkhod.domain.weights.Inventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlannerTest {

    private val planner = SessionPlanner(bundledConfig)
    private val squat = exercise("squat", pattern = MovementPattern.SQUAT, equipment = setOf("barbell", "rack"), primary = setOf(Muscle.QUADS, Muscle.GLUTES))
    private val bench = exercise("bench")
    private val curl = exercise("curl", MovementPattern.ISOLATION, ExerciseKind.ISOLATION, setOf("cable_station"), setOf(Muscle.BICEPS))
    private val infos = listOf(squat, bench, curl).associateBy { it.id }
    private val day = listOf(slot("squat", weight = 100.0), slot("bench", weight = 80.0), slot("curl", sets = 2, weight = 20.0, role = SlotRole.ISOLATION))

    private fun plan(ctx: SessionContext) = planner.plan(day, infos, Inventory(), ctx)

    @Test
    fun normalWeekKeepsThePlan() {
        val p = plan(SessionContext(weekIndex = 0, weeks = 6))
        assertFalse(p.deload)
        assertEquals(day.map { it.sets }, p.slots.map { it.sets })
        assertEquals(day.map { it.targetWeightKg }, p.slots.map { it.targetWeightKg })
    }

    @Test
    fun lastWeekIsAPlannedDeload() {
        val p = plan(SessionContext(weekIndex = 5, weeks = 6))
        assertTrue(p.deload)
        assertEquals(listOf(2, 2, 1), p.slots.map { it.sets })
        assertEquals(90.0, p.slots[0].targetWeightKg!!, 1e-9)
        // 80 × 0.9 = 72 is not buildable; the nearest lower weight is 70.
        assertEquals(70.0, p.slots[1].targetWeightKg!!, 1e-9)
    }

    @Test
    fun acceptedUnplannedDeloadAppliesTheSameCut() {
        val p = plan(SessionContext(weekIndex = 2, weeks = 6, unplannedDeload = true))
        assertTrue(p.deload)
        assertEquals(90.0, p.slots[0].targetWeightKg!!, 1e-9)
    }

    @Test
    fun oneBadAnswerAddsReserveOnly() {
        val p = plan(SessionContext(0, 6, checkin = Checkin(sleep = 2)))
        assertEquals(day.map { it.sets }, p.slots.map { it.sets })
        assertEquals(day.map { it.rirMin + 1 }, p.slots.map { it.rirMin })
    }

    @Test
    fun veryHardDayCutsASetAndAddsReserve() {
        val p = plan(SessionContext(0, 6, checkin = Checkin(sleep = 2, readiness = 2)))
        assertEquals(listOf(2, 2, 1), p.slots.map { it.sets })
        assertEquals(day.map { it.rirMax + 1 }, p.slots.map { it.rirMax })
    }

    @Test
    fun tiredLegsEaseOnlyLegExercises() {
        val p = plan(SessionContext(0, 6, checkin = Checkin(legs = 2)))
        assertEquals(2, p.slots[0].sets)
        assertEquals(90.0, p.slots[0].targetWeightKg!!, 1e-9)
        assertEquals(3, p.slots[1].sets)
        assertEquals(80.0, p.slots[1].targetWeightKg!!, 1e-9)
    }

    @Test
    fun longEffortTomorrowFromTheProfileEasesLegs() {
        val p = plan(SessionContext(0, 6, longEffortTomorrow = true))
        assertEquals(2, p.slots[0].sets)
        assertEquals(day[0].rirMin + 1, p.slots[0].rirMin)
        assertTrue(p.notes.any { it.contains("завтра длинная нагрузка") })
    }
}

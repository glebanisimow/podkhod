package app.podkhod.domain.stats

import app.podkhod.domain.OneRepMax
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.plan.MuscleGroup
import app.podkhod.domain.plan.bundledConfig
import app.podkhod.domain.plan.exercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressStatsTest {

    private val bench = exercise("bench", primary = setOf(Muscle.CHEST)).copy(secondary = setOf(Muscle.TRICEPS, Muscle.DELTS_FRONT))
    private val pullup = exercise("pullup", pattern = MovementPattern.V_PULL, primary = setOf(Muscle.LATS), load = LoadType.BODYWEIGHT_PLUS)
    private val plank = exercise("plank", primary = setOf(Muscle.ABS), load = LoadType.NONE, measure = Measure.SECONDS)

    private fun set(session: Long, at: Long, id: String, weight: Double?, reps: Int?, kind: SetKind = SetKind.WORK, seconds: Int? = null) =
        HistorySet(session, at, id, kind, weight, reps, seconds)

    @Test
    fun `e1rm per session uses the best work set and ignores warm-ups`() {
        val sets = listOf(
            set(2, 200, "bench", 100.0, 1, SetKind.WARMUP),
            set(2, 200, "bench", 80.0, 8),
            set(2, 200, "bench", 85.0, 5),
            set(1, 100, "bench", 75.0, 8),
            set(1, 100, "other", 200.0, 5),
        )
        val p = ProgressStats.exercise(bench, sets)
        assertEquals(ProgressMetric.E1RM, p.metric)
        assertEquals(listOf(1L, 2L), p.points.map { it.sessionId })
        val best2 = maxOf(OneRepMax.estimate(80.0, 8).average, OneRepMax.estimate(85.0, 5).average)
        assertEquals(best2, p.points[1].e1rmKg!!, 1e-9)
        assertEquals(85.0, p.points[1].topWeightKg!!, 1e-9)
        assertEquals(2, p.points[1].workSets)
        assertEquals(80.0 * 8 + 85.0 * 5, p.points[1].volumeKg, 1e-9)
        assertEquals(85.0, p.bestWeightKg!!, 1e-9)
        assertEquals(best2 - OneRepMax.estimate(75.0, 8).average, p.change!!, 1e-9)
    }

    @Test
    fun `bodyweight lifts track reps and holds track seconds`() {
        val reps = ProgressStats.exercise(pullup, listOf(set(1, 1, "pullup", 0.0, 6), set(2, 2, "pullup", 5.0, 8)))
        assertEquals(ProgressMetric.REPS, reps.metric)
        assertEquals(listOf(6.0, 8.0), reps.points.map { it.value(ProgressMetric.REPS) })
        assertNull(reps.points[1].e1rmKg)
        assertEquals(5.0, reps.bestWeightKg!!, 1e-9)

        val hold = ProgressStats.exercise(plank, listOf(set(1, 1, "plank", null, null, seconds = 40), set(2, 2, "plank", null, null, seconds = 55)))
        assertEquals(ProgressMetric.SECONDS, hold.metric)
        assertEquals(15.0, hold.change!!, 1e-9)
    }

    @Test
    fun `a single point has no change`() {
        assertNull(ProgressStats.exercise(bench, listOf(set(1, 1, "bench", 80.0, 5))).change)
    }

    @Test
    fun `exercise list is ordered by last session`() {
        val list = ProgressStats.exercises(
            listOf(
                set(1, 100, "bench", 80.0, 5),
                set(2, 300, "pullup", 0.0, 8),
                set(3, 200, "bench", 80.0, 6),
                set(3, 200, "plank", null, null, SetKind.WARMUP),
            ),
        )
        assertEquals(listOf("pullup", "bench"), list.map { it.exerciseId })
        assertEquals(2, list[1].sessions)
    }

    @Test
    fun `weekly volume credits secondary muscles like the generator`() {
        val config = bundledConfig
        val sets = List(3) { WeekSet(0, "bench", SetKind.WORK) } + WeekSet(0, "bench", SetKind.WARMUP) + List(4) { WeekSet(1, "pullup", SetKind.WORK) }
        val weeks = ProgressStats.weeklyVolume(
            sets,
            mapOf(0 to 2, 1 to 1, 2 to 0),
            mapOf("bench" to bench, "pullup" to pullup),
            config,
            mapOf(MuscleGroup.CHEST to 4..8, MuscleGroup.BACK to 2..3),
        )
        assertEquals(listOf(0, 1, 2), weeks.map { it.weekIndex })
        val w0 = weeks[0].groups.associateBy { it.group }
        assertEquals(3.0, w0.getValue(MuscleGroup.CHEST).sets, 1e-9)
        assertEquals(VolumeStatus.BELOW, w0.getValue(MuscleGroup.CHEST).status)
        assertEquals(3.0 * config.credit(bench, MuscleGroup.TRICEPS), w0.getValue(MuscleGroup.TRICEPS).sets, 1e-9)
        assertEquals(VolumeStatus.NO_TARGET, w0.getValue(MuscleGroup.TRICEPS).status)
        assertEquals(VolumeStatus.ABOVE, weeks[1].groups.first { it.group == MuscleGroup.BACK }.status)
        assertEquals(0.0, weeks[2].groups.sumOf { it.sets }, 1e-9)
    }
}

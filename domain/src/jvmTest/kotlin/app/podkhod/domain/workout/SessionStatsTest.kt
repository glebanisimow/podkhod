package app.podkhod.domain.workout

import app.podkhod.domain.model.SetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsTest {

    private fun set(ex: String, w: Double?, r: Int?, kind: SetKind = SetKind.WORK) = LoggedSet(ex, kind, w, r, null)

    @Test
    fun volumeIgnoresWarmupsAndBodyweightSets() {
        val sets = listOf(
            set("squat", 20.0, 10, SetKind.WARMUP),
            set("squat", 100.0, 5),
            set("squat", 100.0, 5),
            set("pull_up", 0.0, 8),
            set("pull_up", null, 8),
        )
        val s = SessionStats.summarize(sets, startedAtMillis = 0, finishedAtMillis = 45 * 60_000L)
        assertEquals(1000.0, s.volumeKg, 1e-9)
        assertEquals(4, s.workSets)
        assertEquals(2, s.exercisesDone)
        assertEquals(2700L, s.durationSec)
    }

    @Test
    fun warmupHasNoEstimated1rm() {
        assertNull(SessionStats.estimated1rm(set("squat", 60.0, 5, SetKind.WARMUP)))
    }

    @Test
    fun firstEverSessionIsBaselineNotRecord() {
        assertTrue(SessionStats.records(listOf(set("squat", 100.0, 5)), emptyList()).isEmpty())
    }

    @Test
    fun heavierWeightIsWeightRecord() {
        val records = SessionStats.records(listOf(set("squat", 105.0, 3)), listOf(set("squat", 100.0, 5)))
        assertEquals(listOf(RecordKind.WEIGHT), records.map { it.kind })
        assertEquals(100.0, records.single().previous!!, 1e-9)
    }

    @Test
    fun moreRepsAtSameWeightIsEstimated1rmRecord() {
        val records = SessionStats.records(listOf(set("bench", 80.0, 8)), listOf(set("bench", 80.0, 6)))
        assertEquals(listOf(RecordKind.ESTIMATED_1RM), records.map { it.kind })
    }

    @Test
    fun bodyweightRepsRecord() {
        val records = SessionStats.records(listOf(set("pull_up", 0.0, 12)), listOf(set("pull_up", 0.0, 10)))
        assertEquals(listOf(RecordKind.BODYWEIGHT_REPS), records.map { it.kind })
    }

    @Test
    fun noRecordWhenWorse() {
        assertTrue(SessionStats.records(listOf(set("bench", 75.0, 5)), listOf(set("bench", 80.0, 6))).isEmpty())
    }
}

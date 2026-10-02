package app.podkhod.domain.export

import app.podkhod.domain.model.SetKind
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class StrengthExportTest {

    private val t0 = 1_790_000_000_000L

    private fun set(id: String, cat: ExerciseCategory?, at: Long, weight: Double? = 80.0, reps: Int? = 5, kind: SetKind = SetKind.WORK, sec: Int? = null) =
        ExportSetInput(id, cat, kind, weight, reps, sec, t0 + at * 1000)

    @Test
    fun `warm-ups are dropped and set starts are estimated without overlap`() {
        val a = StrengthExport.build(
            7, "A", t0, t0 + 3600_000,
            listOf(
                set("squat", ExerciseCategory.SQUAT, 60, weight = 40.0, kind = SetKind.WARMUP),
                set("squat", ExerciseCategory.SQUAT, 200),
                set("squat", ExerciseCategory.SQUAT, 205, reps = 8),
                set("plank", ExerciseCategory.PLANK, 400, weight = null, reps = null, sec = 45),
            ),
        )
        assertEquals("podkhod-7", a.externalId)
        assertEquals(3, a.sets.size)
        assertEquals(t0 + 185_000, a.sets[0].startAt)
        // The second set "started" 24 s before its end but cannot overlap the first one.
        assertEquals(a.sets[0].endAt, a.sets[1].startAt)
        assertEquals(5, a.sets[1].durationSec)
        assertEquals(45, a.sets[2].durationSec)
        assertFalse(a.sets[2].estimatedDuration)
        assertNull(a.sets[2].weightKg)
        assertEquals(3600, a.elapsedSec)
        assertEquals(15 + 5 + 45, a.activeSec)
        assertTrue(a.uncategorized.isEmpty())
    }

    @Test
    fun `strava json has the required fields`() {
        val a = StrengthExport.build(1, "B", t0, t0 + 1800_000, listOf(set("bench", ExerciseCategory.BENCH_PRESS, 300, weight = 62.5, reps = 8)))
        val json = StrengthExport.stravaJson(a, ZoneId.of("Europe/Berlin"))
        assertEquals("1.0", json["version"]!!.jsonPrimitive.content)
        assertEquals(1800, json["elapsed_time"]!!.jsonPrimitive.int)
        assertTrue(json["start_time"]!!.jsonPrimitive.content.endsWith("+02:00") || json["start_time"]!!.jsonPrimitive.content.endsWith("+01:00"))
        val s = json["sets"]!!.jsonArray.single().jsonObject
        assertEquals("BENCH_PRESS_GENERIC", s["exercise_type"]!!.jsonPrimitive.content)
        assertEquals(8, s["repetitions"]!!.jsonPrimitive.int)
        assertEquals("62.5", s["weight"]!!.jsonPrimitive.content)
        assertEquals(24, s["duration"]!!.jsonPrimitive.int)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `uncategorized exercises block the json`() {
        val a = StrengthExport.build(1, "B", t0, t0 + 60_000, listOf(set("mystery", null, 30)))
        assertEquals(setOf("mystery"), a.uncategorized)
        StrengthExport.stravaJson(a, ZoneId.of("UTC"))
    }

    @Test
    fun `fit weight uses a 1-16 kg scale`() {
        assertEquals(1000, StrengthExport.fitWeight(62.5))
        assertEquals(36, StrengthExport.fitWeight(2.25))
    }
}

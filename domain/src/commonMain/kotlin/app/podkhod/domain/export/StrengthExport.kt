package app.podkhod.domain.export

import app.podkhod.domain.model.SetKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.offsetAt
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Exercise categories shared by Strava's JSON strength upload and the FIT `exercise_category` enum.
 * Codes are persisted in the catalog (`strava`); never rename an existing constant. [fit] is the FIT
 * enum value; categories Strava has but FIT does not map to the closest FIT one.
 */
enum class ExerciseCategory(val fit: Int) {
    BENCH_PRESS(0), CALF_RAISE(1), CARRY(3), CHOP(4), CORE(5), CURL(7), DEADLIFT(8), FLYE(9),
    HIP_RAISE(10), HIP_STABILITY(11), HIP_SWING(12), HYPEREXTENSION(13), LATERAL_RAISE(14), LEG_CURL(15),
    LEG_RAISE(16), LUNGE(17), OLYMPIC_LIFT(18), PLANK(19), PULL_UP(21), PUSH_UP(22), ROW(23),
    SHOULDER_PRESS(24), SHOULDER_STABILITY(25), SHRUG(26), SIT_UP(27), SQUAT(28), TOTAL_BODY(29),
    TRICEPS_EXTENSION(30),

    /** Strava only; FIT has hip thrusts under hip_raise. */
    HIP_THRUST(10),

    /** Strava only; FIT has no leg-extension category, so it goes out as unknown. */
    QUAD_EXTENSION(FIT_UNKNOWN);

    val code: String get() = name.lowercase()

    /**
     * Strava JSON `exercise_type`. Only the category-level "generic" value is used: the exact per-exercise
     * names are checked against the uploads page when the upload itself is built (stage 5).
     */
    val stravaType: String get() = "${name}_GENERIC"

    companion object {
        fun fromCode(code: String?): ExerciseCategory? = entries.firstOrNull { it.code == code }
    }
}

private const val FIT_UNKNOWN = 65534

/** One logged set with what the export needs from the catalog. */
data class ExportSetInput(
    val exerciseId: String,
    val category: ExerciseCategory?,
    val kind: SetKind,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val completedAt: Long,
)

data class ExportSet(
    val exerciseId: String,
    val category: ExerciseCategory?,
    val reps: Int?,
    /** External or added weight; null for bodyweight-only and unloaded sets. */
    val weightKg: Double?,
    /** Measured for timed sets, estimated from reps otherwise. */
    val durationSec: Int,
    val startAt: Long,
    val endAt: Long,
    val estimatedDuration: Boolean,
)

data class StrengthActivity(
    val externalId: String,
    val name: String,
    val startAt: Long,
    val elapsedSec: Int,
    val activeSec: Int,
    val sets: List<ExportSet>,
    /** Exercises without a category; the upload must refuse them rather than guess. */
    val uncategorized: Set<String>,
)

/**
 * Turns a finished session into a strength activity. The log stores only when a set was completed, so
 * the start of a set is estimated: timed sets use their duration, rep sets [SECONDS_PER_REP] per rep,
 * never earlier than the previous set's end or the session start. Warm-ups are left out: neither Strava
 * JSON nor its display has a warm-up flag, and they would inflate the totals.
 */
object StrengthExport {
    const val SECONDS_PER_REP = 3
    private const val MIN_SET_SEC = 5

    fun build(sessionId: Long, title: String, startedAt: Long, finishedAt: Long, sets: List<ExportSetInput>): StrengthActivity {
        var cursor = startedAt
        val out = sets.filter { it.kind.countsAsWork }.sortedBy { it.completedAt }.map { s ->
            val timed = s.durationSec != null && s.durationSec > 0
            val duration = if (timed) s.durationSec!! else maxOf((s.reps ?: 0) * SECONDS_PER_REP, MIN_SET_SEC)
            val end = s.completedAt.coerceAtLeast(cursor)
            val start = (end - duration * 1000L).coerceAtLeast(cursor)
            cursor = end
            ExportSet(
                exerciseId = s.exerciseId,
                category = s.category,
                reps = s.reps?.takeIf { it > 0 },
                weightKg = s.weightKg?.takeIf { it > 0.0 },
                durationSec = ((end - start) / 1000L).toInt(),
                startAt = start,
                endAt = end,
                estimatedDuration = !timed,
            )
        }
        val end = maxOf(finishedAt, cursor)
        return StrengthActivity(
            externalId = "podkhod-$sessionId",
            name = title,
            startAt = startedAt,
            elapsedSec = ((end - startedAt) / 1000L).toInt(),
            activeSec = out.sumOf { it.durationSec },
            sets = out,
            uncategorized = out.filter { it.category == null }.map { it.exerciseId }.toSet(),
        )
    }

    /** Body of Strava's JSON strength upload (`data_type=json`, version 1.0); weights in kilograms. */
    fun stravaJson(activity: StrengthActivity, zone: TimeZone): JsonObject {
        require(activity.uncategorized.isEmpty()) { "uncategorized exercises: ${activity.uncategorized}" }
        val start = Instant.fromEpochMilliseconds(activity.startAt)
        return buildJsonObject {
            put("version", "1.0")
            put("start_time", isoOffset(activity.startAt, zone))
            put("utc_offset", zone.offsetAt(start).totalSeconds)
            put("elapsed_time", activity.elapsedSec)
            put("active_time", activity.activeSec)
            put(
                "sets",
                buildJsonArray {
                    activity.sets.forEach { s ->
                        add(
                            buildJsonObject {
                                put("exercise_type", s.category!!.stravaType)
                                s.reps?.let { put("repetitions", it) }
                                s.weightKg?.let { put("weight", (it * 100).roundToInt() / 100.0) }
                                put("duration", s.durationSec)
                                put("start_time", isoOffset(s.startAt, zone))
                            },
                        )
                    }
                },
            )
        }
    }

    /** ISO 8601 with the zone's offset at that moment, e.g. `2026-10-08T18:30:00+03:00`. */
    private fun isoOffset(millis: Long, zone: TimeZone): String {
        val instant = Instant.fromEpochMilliseconds(millis)
        return instant.format(DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET, zone.offsetAt(instant))
    }

    /**
     * FIT `set` message values: weight is uint16 with scale 16 (1/16 kg, at most ~4095 kg), duration is
     * uint32 milliseconds. Checked here so the stage 5 encoder never has to truncate silently.
     */
    fun fitWeight(weightKg: Double): Int {
        val v = (weightKg * 16).roundToInt()
        require(v in 0..0xFFFE) { "weight $weightKg kg does not fit FIT set.weight" }
        return v
    }
}

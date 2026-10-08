package app.podkhod.domain.workout

import app.podkhod.domain.OneRepMax
import app.podkhod.domain.model.SetKind

data class LoggedSet(
    val exerciseId: String,
    val kind: SetKind,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
)

data class SessionSummary(
    val durationSec: Long,
    val workSets: Int,
    val volumeKg: Double,
    val exercisesDone: Int,
)

data class ExerciseRecord(
    val exerciseId: String,
    val kind: RecordKind,
    val value: Double,
    val previous: Double?,
)

enum class RecordKind { WEIGHT, ESTIMATED_1RM, BODYWEIGHT_REPS }

object SessionStats {

    fun summarize(sets: List<LoggedSet>, startedAtMillis: Long, finishedAtMillis: Long): SessionSummary {
        val work = sets.filter { it.kind.countsAsWork }
        return SessionSummary(
            durationSec = ((finishedAtMillis - startedAtMillis) / 1000L).coerceAtLeast(0),
            workSets = work.size,
            volumeKg = work.sumOf { volumeOf(it) },
            exercisesDone = work.map { it.exerciseId }.distinct().size,
        )
    }

    fun volumeOf(set: LoggedSet): Double {
        val w = set.weightKg ?: return 0.0
        val r = set.reps ?: return 0.0
        return if (w > 0 && r > 0) w * r else 0.0
    }

    /** Best estimated 1RM of a set, or null when the set has no external load or no reps. */
    fun estimated1rm(set: LoggedSet): Double? {
        val w = set.weightKg ?: return null
        val r = set.reps ?: return null
        if (w <= 0.0 || r <= 0 || !set.kind.countsAsWork) return null
        return OneRepMax.estimate(w, r).average
    }

    /**
     * Records of [current] against all earlier [history] work sets of the same exercises.
     * An exercise without history produces no records: the first session is a baseline, not a PR.
     */
    fun records(current: List<LoggedSet>, history: List<LoggedSet>): List<ExerciseRecord> {
        val result = mutableListOf<ExerciseRecord>()
        val historyByExercise = history.filter { it.kind.countsAsWork }.groupBy { it.exerciseId }
        current.filter { it.kind.countsAsWork }.groupBy { it.exerciseId }.forEach { (exerciseId, sets) ->
            val past = historyByExercise[exerciseId].orEmpty()
            if (past.isEmpty()) return@forEach

            val bestWeight = sets.mapNotNull { it.weightKg?.takeIf { w -> w > 0 && (it.reps ?: 0) > 0 } }.maxOrNull()
            val pastWeight = past.mapNotNull { it.weightKg?.takeIf { w -> w > 0 && (it.reps ?: 0) > 0 } }.maxOrNull()
            if (bestWeight != null && pastWeight != null && bestWeight > pastWeight + EPS) {
                result += ExerciseRecord(exerciseId, RecordKind.WEIGHT, bestWeight, pastWeight)
            }

            val best1rm = sets.mapNotNull(::estimated1rm).maxOrNull()
            val past1rm = past.mapNotNull(::estimated1rm).maxOrNull()
            if (best1rm != null && past1rm != null && best1rm > past1rm + EPS &&
                result.none { it.exerciseId == exerciseId }
            ) {
                result += ExerciseRecord(exerciseId, RecordKind.ESTIMATED_1RM, best1rm, past1rm)
            }

            val bestReps = sets.filter { (it.weightKg ?: 0.0) <= 0.0 }.mapNotNull { it.reps }.maxOrNull()
            val pastReps = past.filter { (it.weightKg ?: 0.0) <= 0.0 }.mapNotNull { it.reps }.maxOrNull()
            if (bestReps != null && pastReps != null && bestReps > pastReps) {
                result += ExerciseRecord(exerciseId, RecordKind.BODYWEIGHT_REPS, bestReps.toDouble(), pastReps.toDouble())
            }
        }
        return result
    }

    private const val EPS = 1e-6
}

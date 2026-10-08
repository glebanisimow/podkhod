package app.podkhod.domain.stats

import app.podkhod.domain.OneRepMax
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.plan.GeneratorConfig
import app.podkhod.domain.plan.MuscleGroup

/** One logged set of a finished session, as the progress screens read it. */
data class HistorySet(
    val sessionId: Long,
    val startedAt: Long,
    val exerciseId: String,
    val kind: SetKind,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
)

/** What the chart of an exercise shows: the value that best tracks strength for this kind of lift. */
enum class ProgressMetric {
    /** Loaded lifts on reps: estimated 1RM, the top working weight is drawn alongside. */
    E1RM,
    /** Bodyweight (with or without added weight) and unloaded lifts: best reps in a work set. */
    REPS,
    /** Holds and carries on time: best seconds in a work set. */
    SECONDS;

    companion object {
        fun of(info: ExerciseInfo): ProgressMetric = when {
            info.measure == Measure.SECONDS -> SECONDS
            info.load == LoadType.EXTERNAL -> E1RM
            else -> REPS
        }
    }
}

/** Best values of one session for one exercise; null where the session has no such set. */
data class ExercisePoint(
    val sessionId: Long,
    val startedAt: Long,
    val e1rmKg: Double?,
    val topWeightKg: Double?,
    val bestReps: Int?,
    val bestSeconds: Int?,
    val workSets: Int,
    val volumeKg: Double,
) {
    fun value(metric: ProgressMetric): Double? = when (metric) {
        ProgressMetric.E1RM -> e1rmKg
        ProgressMetric.REPS -> bestReps?.toDouble()
        ProgressMetric.SECONDS -> bestSeconds?.toDouble()
    }
}

data class ExerciseProgress(
    val exerciseId: String,
    val metric: ProgressMetric,
    /** Oldest first; sessions with only warm-ups are left out. */
    val points: List<ExercisePoint>,
    val bestE1rmKg: Double?,
    val bestWeightKg: Double?,
    val bestReps: Int?,
    val bestSeconds: Int?,
) {
    /** Change of the metric from the first to the last point; null with fewer than two points. */
    val change: Double?
        get() {
            val values = points.mapNotNull { it.value(metric) }
            return if (values.size < 2) null else values.last() - values.first()
        }
}

/** An exercise in the progress list: when it was last done and how often. */
data class ExerciseSummary(val exerciseId: String, val sessions: Int, val lastAt: Long)

/** Weekly work sets per group against the program target. */
data class GroupVolume(val group: MuscleGroup, val sets: Double, val targetLow: Int?, val targetHigh: Int?) {
    val status: VolumeStatus
        get() = when {
            targetLow == null || targetHigh == null -> VolumeStatus.NO_TARGET
            sets + EPS < targetLow -> VolumeStatus.BELOW
            sets - EPS > targetHigh -> VolumeStatus.ABOVE
            else -> VolumeStatus.WITHIN
        }
}

enum class VolumeStatus { BELOW, WITHIN, ABOVE, NO_TARGET }

/** A set with the program week it was done in, for volume by week. */
data class WeekSet(val weekIndex: Int, val exerciseId: String, val kind: SetKind)

data class WeekVolume(val weekIndex: Int, val sessions: Int, val groups: List<GroupVolume>)

object ProgressStats {

    /**
     * Per-session bests of one exercise. Warm-ups never count; sets without reps or weight still count as
     * work sets for the session but give no e1RM.
     */
    fun exercise(info: ExerciseInfo, sets: List<HistorySet>): ExerciseProgress {
        val metric = ProgressMetric.of(info)
        val points = sets.filter { it.exerciseId == info.id && it.kind.countsAsWork }
            .groupBy { it.sessionId }
            .map { (sessionId, work) -> point(sessionId, work, info) }
            .sortedWith(compareBy({ it.startedAt }, { it.sessionId }))
        return ExerciseProgress(
            exerciseId = info.id,
            metric = metric,
            points = points,
            bestE1rmKg = points.mapNotNull { it.e1rmKg }.maxOrNull(),
            bestWeightKg = points.mapNotNull { it.topWeightKg }.maxOrNull(),
            bestReps = points.mapNotNull { it.bestReps }.maxOrNull(),
            bestSeconds = points.mapNotNull { it.bestSeconds }.maxOrNull(),
        )
    }

    /** Exercises with at least one work set, most recently done first. */
    fun exercises(sets: List<HistorySet>): List<ExerciseSummary> =
        sets.filter { it.kind.countsAsWork }
            .groupBy { it.exerciseId }
            .map { (id, rows) -> ExerciseSummary(id, rows.map { it.sessionId }.distinct().size, rows.maxOf { it.startedAt }) }
            .sortedWith(compareByDescending<ExerciseSummary> { it.lastAt }.thenBy { it.exerciseId })

    /**
     * Work sets per group for each program week, credited the way the generator plans them (primary
     * muscle 1, secondary by config). [targets] are the active program's ranges, low..high.
     */
    fun weeklyVolume(
        sets: List<WeekSet>,
        sessionsPerWeek: Map<Int, Int>,
        infos: Map<String, ExerciseInfo>,
        config: GeneratorConfig,
        targets: Map<MuscleGroup, IntRange>,
    ): List<WeekVolume> {
        val byWeek = sets.filter { it.kind.countsAsWork }.groupBy { it.weekIndex }
        return (byWeek.keys + sessionsPerWeek.keys).sorted().map { week ->
            val rows = byWeek[week].orEmpty()
            WeekVolume(
                weekIndex = week,
                sessions = sessionsPerWeek[week] ?: 0,
                groups = MuscleGroup.entries.map { g ->
                    val total = rows.sumOf { r -> infos[r.exerciseId]?.let { config.credit(it, g) } ?: 0.0 }
                    val target = targets[g]
                    GroupVolume(g, total, target?.first, target?.last)
                },
            )
        }
    }

    private fun point(sessionId: Long, work: List<HistorySet>, info: ExerciseInfo): ExercisePoint {
        val loaded = work.filter { (it.weightKg ?: 0.0) > 0.0 && (it.reps ?: 0) > 0 }
        val e1rm = if (info.load == LoadType.EXTERNAL && info.measure == Measure.REPS) {
            loaded.maxOfOrNull { OneRepMax.estimate(it.weightKg!!, it.reps!!).average }
        } else {
            null
        }
        return ExercisePoint(
            sessionId = sessionId,
            startedAt = work.first().startedAt,
            e1rmKg = e1rm,
            topWeightKg = loaded.maxOfOrNull { it.weightKg!! },
            bestReps = work.mapNotNull { it.reps?.takeIf { r -> r > 0 } }.maxOrNull(),
            bestSeconds = work.mapNotNull { it.durationSec?.takeIf { s -> s > 0 } }.maxOrNull(),
            workSets = work.size,
            volumeKg = loaded.sumOf { it.weightKg!! * it.reps!! },
        )
    }
}

private const val EPS = 1e-9

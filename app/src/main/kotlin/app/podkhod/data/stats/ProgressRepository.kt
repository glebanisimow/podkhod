package app.podkhod.data.stats

import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.plan.GeneratorConfigStore
import app.podkhod.data.plan.PlanRepository
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.plan.MuscleGroup
import app.podkhod.domain.stats.ExerciseProgress
import app.podkhod.domain.stats.ExerciseSummary
import app.podkhod.domain.stats.HistorySet
import app.podkhod.domain.stats.ProgressStats
import app.podkhod.domain.stats.WeekSet
import app.podkhod.domain.stats.WeekVolume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Weekly volume of the current mesocycle; [weekIndex] is the program's current week, 0-based. */
data class MesocycleVolume(
    val weeks: List<WeekVolume>,
    val weekIndex: Int,
    val totalWeeks: Int,
)

/** Read-only views over finished sessions: per-exercise trends and weekly volume against the plan. */
class ProgressRepository(
    db: AppDatabase,
    private val catalog: CatalogRepository,
    private val configs: GeneratorConfigStore,
) {
    private val workoutDao = db.workoutDao()
    private val programDao = db.programDao()

    private val historySets: Flow<List<HistorySet>> = workoutDao.observeHistorySets().map { rows ->
        rows.map { HistorySet(it.sessionId, it.startedAt, it.exerciseId, SetKind.fromCode(it.kind), it.weightKg, it.reps, it.durationSec) }
    }

    val exercises: Flow<List<ExerciseSummary>> = historySets.map(ProgressStats::exercises)

    fun exercise(exerciseId: String): Flow<ExerciseProgress?> = historySets.map { sets ->
        val info = catalog.exerciseInfos()[exerciseId] ?: return@map null
        ProgressStats.exercise(info, sets)
    }

    /** Null while the active program is a template: it has no volume targets and no mesocycle. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val mesocycle: Flow<MesocycleVolume?> = programDao.observeActive().flatMapLatest { program ->
        if (program == null || program.source != PlanRepository.SOURCE_GENERATED) return@flatMapLatest flowOf(null)
        val targets = PlanRepository.decodeExplanation(program.explanationJson)?.volume.orEmpty()
            .mapNotNull { v -> MuscleGroup.fromCode(v.group)?.let { it to v.targetLow..v.max } }
            .toMap()
        combine(workoutDao.observeWeekSets(program.mesocycleIndex), workoutDao.observeWeekSessions(program.mesocycleIndex)) { sets, counts ->
            val weekSets = sets.map { WeekSet(it.weekIndex, it.exerciseId, SetKind.fromCode(it.kind)) }
            val sessions = counts.associate { it.weekIndex to it.count }
            MesocycleVolume(
                weeks = ProgressStats.weeklyVolume(weekSets, sessions, catalog.exerciseInfos(), configs.config(), targets),
                weekIndex = program.weekIndex,
                totalWeeks = program.weeks,
            )
        }
    }
}

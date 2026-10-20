package app.podkhod.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.catalog.MuscleSet
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.program.ActiveProgram
import app.podkhod.data.program.ProgramRepository
import app.podkhod.data.settings.AppSettings
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.data.workout.SessionDetail
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.domain.plan.Checkin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayState(
    val loading: Boolean = true,
    val program: ActiveProgram? = null,
    val active: SessionDetail? = null,
    val exercises: Map<String, ExerciseEntity> = emptyMap(),
) {
    val generated: Boolean get() = program?.program?.source == PlanRepository.SOURCE_GENERATED
    val mesocycleDone: Boolean get() = generated && program!!.program.weekIndex >= program.program.weeks
}

/** The resume offer is shown once per process start, not every time Today is opened. */
internal object ResumeOffer {
    var shown = false
}

class TodayViewModel(
    private val programs: ProgramRepository,
    private val workouts: WorkoutRepository,
    private val plans: PlanRepository,
    catalog: CatalogRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeDetail = workouts.activeSession.flatMapLatest { s ->
        if (s == null) flowOf(null) else workouts.observeDetail(s.id)
    }

    val state: StateFlow<TodayState> =
        combine(programs.activeProgram, activeDetail, catalog.exercisesById) { program, active, exercises ->
            TodayState(loading = program == null && active == null, program = program, active = active, exercises = exercises)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    /** For the technique sheet opened from the plan list. */
    val muscles: StateFlow<Map<String, MuscleSet>> =
        catalog.musclesByExercise.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private var starting = false

    fun start(programDayId: Long, checkin: Checkin?, onStarted: (Long) -> Unit) {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                onStarted(workouts.startFromProgramDay(programDayId, checkin))
            } finally {
                starting = false
            }
        }
    }

    fun skip() {
        viewModelScope.launch {
            programs.skipNextDay()
            plans.refreshAdaptivePlan()
        }
    }

    fun acceptDeload() {
        viewModelScope.launch { plans.acceptDeload() }
    }

    fun declineDeload() {
        viewModelScope.launch { plans.declineDeload() }
    }

    fun newMesocycle() {
        viewModelScope.launch { plans.startNewMesocycle() }
    }
}

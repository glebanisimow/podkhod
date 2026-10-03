package app.podkhod.ui.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.catalog.MuscleSet
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.db.PlannedExerciseEntity
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.plan.ProgramExplanation
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.data.program.ActiveProgram
import app.podkhod.data.program.ProgramRepository
import app.podkhod.data.settings.AppSettings
import app.podkhod.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProgramUiState(
    val loading: Boolean = true,
    val program: ActiveProgram? = null,
    val exercises: Map<String, ExerciseEntity> = emptyMap(),
    val explanation: ProgramExplanation? = null,
) {
    val generated: Boolean get() = program?.program?.source == PlanRepository.SOURCE_GENERATED
}

class ProgramViewModel(
    programs: ProgramRepository,
    private val catalog: CatalogRepository,
    private val plans: PlanRepository,
    private val profiles: ProfileRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<ProgramUiState> =
        combine(programs.activeProgram, catalog.exercisesById) { program, exercises ->
            ProgramUiState(
                loading = program == null,
                program = program,
                exercises = exercises,
                explanation = PlanRepository.decodeExplanation(program?.program?.explanationJson),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramUiState())

    /** For the technique sheet opened from a slot. */
    val muscles: StateFlow<Map<String, MuscleSet>> =
        catalog.musclesByExercise.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _substitutes = MutableStateFlow<List<ExerciseEntity>>(emptyList())
    val substitutes: StateFlow<List<ExerciseEntity>> = _substitutes.asStateFlow()

    fun setLocked(row: PlannedExerciseEntity, locked: Boolean) = launch { plans.setLocked(row.id, locked) }

    fun changeSets(row: PlannedExerciseEntity, delta: Int) = launch { plans.changeSets(row.id, delta) }

    fun replace(row: PlannedExerciseEntity, exerciseId: String) = launch { plans.replacePlanned(row.id, exerciseId) }

    fun newMesocycle() = launch { plans.startNewMesocycle() }

    /** Catalog substitutes the user's gym and restrictions allow. */
    fun loadSubstitutes(row: PlannedExerciseEntity) = launch {
        _substitutes.value = emptyList()
        val profile = profiles.current()
        val config = plans.config()
        val infos = catalog.exerciseInfos()
        val available = profile?.inventory?.available
        val banned = profile?.restrictionTags.orEmpty().flatMap { config.restrictionTags[it] ?: listOf(it) }.toSet()
        _substitutes.value = catalog.substitutes(row.exerciseId).filter { e ->
            val info = infos[e.id] ?: return@filter false
            (available == null || available.containsAll(info.equipment)) && info.tags.none { it in banned }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

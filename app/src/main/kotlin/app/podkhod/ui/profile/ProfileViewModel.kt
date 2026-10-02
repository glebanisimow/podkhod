package app.podkhod.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.plan.ProgramPreview
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.domain.plan.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProfileStep { GOAL, SCHEDULE, STYLE, BASELINES, INVENTORY, RESTRICTIONS, BODY }

data class ProfileUiState(
    val loading: Boolean = true,
    val draft: Profile = Profile(),
    val step: ProfileStep = ProfileStep.GOAL,
    val busy: Boolean = false,
    /** Shown for confirmation when an existing generated program would change. */
    val preview: ProgramPreview? = null,
    /** Exercise names for the onboarding lifts, by lift code. */
    val liftNames: Map<String, String> = emptyMap(),
    val restrictionKeys: List<String> = emptyList(),
)

/**
 * Drives both the first-run onboarding and the profile editor. Nothing is saved until the user confirms:
 * the profile and the recomputed program are written together.
 */
class ProfileViewModel(
    private val profiles: ProfileRepository,
    private val plans: PlanRepository,
    private val firstRun: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val config = plans.config()
            val names = plans.names()
            _state.update {
                it.copy(
                    loading = false,
                    draft = profiles.current() ?: Profile(),
                    liftNames = config.baselineExercises.mapValues { (_, id) -> names[id] ?: id },
                    restrictionKeys = config.restrictionTags.keys.toList(),
                )
            }
        }
    }

    fun edit(transform: (Profile) -> Profile) = _state.update { it.copy(draft = transform(it.draft)) }

    fun goTo(step: ProfileStep) = _state.update { it.copy(step = step) }

    fun next() = _state.update { s -> s.copy(step = ProfileStep.entries.getOrElse(s.step.ordinal + 1) { s.step }) }

    fun previous() = _state.update { s -> s.copy(step = ProfileStep.entries.getOrElse(s.step.ordinal - 1) { s.step }) }

    fun skipAll(onDone: () -> Unit) {
        _state.update { it.copy(draft = Profile()) }
        finish(onDone)
    }

    /** Builds the program; in the editor a changed program is shown for confirmation first. */
    fun finish(onDone: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val draft = _state.value.draft
            val preview = plans.preview(draft)
            if (!firstRun && preview.replacesProgram) {
                _state.update { it.copy(busy = false, preview = preview) }
            } else {
                save(draft, preview)
                onDone()
            }
        }
    }

    fun confirm(onDone: () -> Unit) {
        val preview = _state.value.preview ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            save(_state.value.draft, preview)
            _state.update { it.copy(preview = null) }
            onDone()
        }
    }

    fun dismissPreview() = _state.update { it.copy(preview = null, busy = false) }

    private suspend fun save(profile: Profile, preview: ProgramPreview) {
        try {
            profiles.save(profile)
            plans.apply(preview, profile)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}

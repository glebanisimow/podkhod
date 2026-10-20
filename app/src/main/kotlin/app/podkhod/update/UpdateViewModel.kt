package app.podkhod.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.podkhod.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val manifest: UpdateManifest? = null,
    val progress: Int = 0,
    val problem: UpdateProblem? = null,
    val installFile: File? = null,
) {
    val busy: Boolean get() = phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING
}

class UpdateViewModel(
    private val repository: UpdateSource,
    private val hasActiveWorkout: suspend () -> Boolean,
    private val installedVersion: Long = BuildConfig.VERSION_CODE.toLong(),
    private val enabled: Boolean = !BuildConfig.DEBUG,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var downloaded: File? = null

    fun check() = runOperation {
        mutableState.value = UpdateState(phase = UpdatePhase.CHECKING)
        val manifest = repository.check()
        mutableState.value = UpdateState(
            phase = if (manifest.isNewerThan(installedVersion)) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT,
            manifest = manifest,
        )
        downloaded = null
    }

    fun download() = runOperation {
        val manifest = state.value.manifest ?: return@runOperation
        if (hasActiveWorkout()) throw UpdateException(UpdateProblem.ACTIVE_WORKOUT)
        mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, problem = null) }
        downloaded = repository.download(manifest) { percent ->
            mutableState.update { it.copy(progress = percent) }
        }
        mutableState.update { it.copy(phase = UpdatePhase.READY) }
        prepareInstall()
    }

    fun install() = runOperation { prepareInstall() }

    private suspend fun prepareInstall() {
        val file = downloaded ?: throw UpdateException(UpdateProblem.INVALID_APK)
        val manifest = state.value.manifest ?: throw UpdateException(UpdateProblem.INVALID_APK)
        if (hasActiveWorkout()) throw UpdateException(UpdateProblem.ACTIVE_WORKOUT)
        repository.validateForInstall(file, manifest)
        mutableState.update { it.copy(problem = null, installFile = file) }
    }

    fun consumeInstallRequest() {
        mutableState.update { it.copy(installFile = null) }
    }

    suspend fun canLaunchInstall(): Boolean {
        if (hasActiveWorkout()) {
            report(UpdateProblem.ACTIVE_WORKOUT)
            return false
        }
        return true
    }

    fun report(problem: UpdateProblem) {
        mutableState.update { it.copy(problem = problem, installFile = null) }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true || !enabled) return
        operation = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = (e as? UpdateException)?.problem ?: UpdateProblem.NETWORK
                mutableState.update {
                    it.copy(
                        phase = when (it.phase) {
                            UpdatePhase.CHECKING -> UpdatePhase.IDLE
                            UpdatePhase.DOWNLOADING -> UpdatePhase.AVAILABLE
                            else -> it.phase
                        },
                        problem = problem,
                        installFile = null,
                    )
                }
            }
        }
    }
}

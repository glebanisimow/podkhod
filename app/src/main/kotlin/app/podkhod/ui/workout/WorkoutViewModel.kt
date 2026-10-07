package app.podkhod.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.catalog.MuscleSet
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.db.SetLogEntity
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.data.settings.AppSettings
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.plan.GeneratorConfig
import app.podkhod.domain.plan.PlannedSlot
import app.podkhod.domain.plan.Progression
import app.podkhod.domain.plan.SlotRole
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.LoadScheme
import app.podkhod.domain.weights.Loads
import app.podkhod.domain.weights.PlateTable
import app.podkhod.domain.weights.Warmup
import app.podkhod.domain.weights.WarmupStep
import kotlinx.coroutines.flow.combine
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.data.workout.SessionDetail
import app.podkhod.data.workout.SlotDetail
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.domain.busy.BusyQueue
import app.podkhod.domain.busy.QueueSlot
import app.podkhod.domain.busy.ReferenceSet
import app.podkhod.domain.busy.SessionSlotInfo
import app.podkhod.domain.busy.SubstituteOption
import app.podkhod.domain.busy.SubstituteRequest
import app.podkhod.domain.busy.Substitution
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.workout.HoldTimer
import app.podkhod.service.HoldSignals
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface WorkoutLoad {
    data object Loading : WorkoutLoad
    data object Missing : WorkoutLoad
    data class Ready(val detail: SessionDetail) : WorkoutLoad
}

data class SetInput(val weightKg: Double, val reps: Int, val seconds: Int)

data class BusyOption(val exercise: ExerciseEntity, val option: SubstituteOption)

/** Per-slot helpers from the plan: warm-up ladder, plate table for barbell lifts, next-step hint. */
data class SlotAids(
    val warmup: List<WarmupStep>,
    val table: PlateTable?,
    val barKg: Double,
    val hint: String?,
)

private data class AidContext(val infos: Map<String, ExerciseInfo>, val inventory: Inventory, val config: GeneratorConfig)

data class BusySheet(
    val slot: SlotDetail,
    val options: List<BusyOption>,
    val waitSlot: SlotDetail?,
    val extras: List<ExerciseEntity>,
    val canReplaceForever: Boolean,
)

class WorkoutViewModel(
    val sessionId: Long,
    private val workouts: WorkoutRepository,
    private val catalog: CatalogRepository,
    settingsRepo: SettingsRepository,
    private val profiles: ProfileRepository,
    private val plans: PlanRepository,
    private val signals: HoldSignals,
) : ViewModel() {

    val load: StateFlow<WorkoutLoad> = workouts.observeDetail(sessionId)
        .map { if (it == null) WorkoutLoad.Missing else WorkoutLoad.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutLoad.Loading)

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val muscles: StateFlow<Map<String, MuscleSet>> =
        catalog.musclesByExercise.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _lastResults = MutableStateFlow<Map<String, List<SetLogEntity>>>(emptyMap())
    val lastResults: StateFlow<Map<String, List<SetLogEntity>>> = _lastResults.asStateFlow()

    private val _inputs = MutableStateFlow<Map<Long, SetInput>>(emptyMap())
    val inputs: StateFlow<Map<Long, SetInput>> = _inputs.asStateFlow()

    private val _warmup = MutableStateFlow(false)
    val warmup: StateFlow<Boolean> = _warmup.asStateFlow()

    private val _busy = MutableStateFlow<BusySheet?>(null)
    val busy: StateFlow<BusySheet?> = _busy.asStateFlow()

    private var lastLogAt = 0L

    private val _hold = MutableStateFlow<HoldTimer?>(null)
    /** The running countdown of a timed set, or null. */
    val hold: StateFlow<HoldTimer?> = _hold.asStateFlow()
    private var holdJob: Job? = null
    private var holdSet: Pair<SlotDetail, SetInput>? = null

    private val aidContext = MutableStateFlow<AidContext?>(null)

    val aids: StateFlow<Map<Long, SlotAids>> = combine(load, aidContext, settings) { l, c, s ->
        val detail = (l as? WorkoutLoad.Ready)?.detail
        if (detail == null || c == null) emptyMap() else detail.slots.associate { it.slot.id to aidsFor(it, c, s.accessoryWarmupSets) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            aidContext.value = AidContext(catalog.exerciseInfos(), profiles.current()?.inventory ?: Inventory(), plans.config())
        }
        viewModelScope.launch { workouts.clearExpiredRest(sessionId) }
        viewModelScope.launch {
            load.filterIsInstance<WorkoutLoad.Ready>()
                .map { r -> r.detail.slots.map { it.exercise.id }.toSet() }
                .distinctUntilChanged()
                .collect { ids -> _lastResults.value = workouts.lastResults(ids, sessionId) }
        }
    }

    fun inputFor(slot: SlotDetail, inputs: Map<Long, SetInput>, last: Map<String, List<SetLogEntity>>): SetInput {
        inputs[slot.slot.id]?.let { return it }
        val previousHere = slot.sets.lastOrNull { SetKind.fromCode(it.kind).countsAsWork } ?: slot.sets.lastOrNull()
        val previousTime = last[slot.exercise.id]?.firstOrNull { SetKind.fromCode(it.kind).countsAsWork }
        val source = previousHere ?: previousTime
        return SetInput(
            weightKg = source?.weightKg ?: slot.slot.targetWeightKg ?: 0.0,
            reps = source?.reps ?: slot.slot.repMax,
            seconds = source?.durationSec ?: slot.slot.repMax,
        )
    }

    fun updateInput(slotId: Long, input: SetInput) {
        _inputs.update { it + (slotId to input) }
    }

    fun stepWeight(slot: SlotDetail, current: SetInput, direction: Int) {
        val step = slot.exercise.stepKg.takeIf { it > 0 } ?: 1.0
        val next = max(0.0, ((current.weightKg / step).roundToInt() + direction) * step)
        updateInput(slot.slot.id, current.copy(weightKg = (next * 100).roundToInt() / 100.0))
    }

    fun stepReps(slot: SlotDetail, current: SetInput, direction: Int) {
        updateInput(slot.slot.id, current.copy(reps = (current.reps + direction).coerceIn(0, 200)))
    }

    fun stepSeconds(slot: SlotDetail, current: SetInput, direction: Int) {
        updateInput(slot.slot.id, current.copy(seconds = (current.seconds + 5 * direction).coerceIn(0, 3600)))
    }

    fun setWarmup(value: Boolean) {
        _warmup.value = value
    }

    /** A warm-up chip fills the inputs and switches the next log to a warm-up set. */
    fun useWarmupStep(slot: SlotDetail, current: SetInput, step: WarmupStep) {
        updateInput(slot.slot.id, current.copy(weightKg = step.weightKg, reps = step.repMax))
        _warmup.value = true
    }

    private fun aidsFor(slot: SlotDetail, c: AidContext, accessoryWarmupSets: Int): SlotAids {
        val info = c.infos[slot.exercise.id] ?: return SlotAids(emptyList(), null, c.inventory.barKg, null)
        val scheme = Loads.scheme(info, c.inventory)
        val target = slot.slot.targetWeightKg
        val table = (scheme as? LoadScheme.Barbell)?.table
        val warmup = when {
            target == null -> emptyList()
            table != null && info.kind == ExerciseKind.COMPOUND -> Warmup.barbell(target, table, c.config.warmup)
            else -> Warmup.light(target, scheme, accessoryWarmupSets, c.config.warmup)
        }
        val planned = PlannedSlot(
            exerciseId = info.id,
            role = SlotRole.ACCESSORY,
            sets = slot.slot.sets,
            repMin = slot.slot.repMin,
            repMax = slot.slot.repMax,
            rirMin = slot.slot.rirMin ?: 0,
            rirMax = slot.slot.rirMax ?: 0,
            restSec = slot.slot.restSec,
            targetWeightKg = target,
        )
        val hint = if (target == null && scheme !is LoadScheme.NoLoad && scheme !is LoadScheme.Added) {
            null
        } else {
            Progression(c.config).hint(info, scheme, planned, target)
        }
        return SlotAids(warmup, table, c.inventory.barKg, hint)
    }

    fun logSet(slot: SlotDetail, input: SetInput) {
        val now = System.currentTimeMillis()
        // A second tap within the debounce window is a double-tap, not a new set.
        if (now - lastLogAt < DOUBLE_TAP_MS) return
        lastLogAt = now
        val measure = Measure.fromCode(slot.exercise.measure)
        val load = LoadType.fromCode(slot.exercise.load)
        val kind = if (_warmup.value) SetKind.WARMUP else SetKind.WORK
        _warmup.value = false
        updateInput(slot.slot.id, input)
        viewModelScope.launch {
            workouts.logSet(
                sessionId = sessionId,
                slotId = slot.slot.id,
                kind = kind,
                weightKg = if (load == LoadType.NONE) null else input.weightKg,
                reps = if (measure == Measure.REPS) input.reps else null,
                durationSec = if (measure == Measure.SECONDS) input.seconds else null,
                restMode = settings.value.supersetRestMode,
            )
        }
    }

    /**
     * Starts the countdown of a timed set at the planned seconds. When it runs out the set is logged by
     * itself, as if «Подход выполнен» was tapped: rest starts and the screen stays put.
     */
    fun startHold(slot: SlotDetail, input: SetInput) {
        if (_hold.value != null) return
        val timer = HoldTimer(slot.slot.id, input.seconds.coerceAtLeast(1), System.currentTimeMillis())
        _hold.value = timer
        holdSet = slot to input
        // A new set ends the rest that was still running.
        if ((load.value as? WorkoutLoad.Ready)?.detail?.session?.restEndsAt != null) skipRest()
        holdJob = viewModelScope.launch {
            val s = settings.value
            sleepUntil(timer.holdStartsAt)
            signals.tick(s.restSound, s.restVibration)
            for (left in HoldTimer.FINAL_TICKS downTo 1) {
                val at = timer.endsAt - left * 1000L
                if (at <= timer.holdStartsAt) continue
                sleepUntil(at)
                signals.tick(s.restSound, false)
            }
            sleepUntil(timer.endsAt)
            signals.finish(s.restSound, s.restVibration)
            endHold(timer.targetSec)
        }
    }

    /** Stops early and logs what was held; during the get-ready countdown it only cancels. */
    fun stopHold() {
        val timer = _hold.value ?: return
        holdJob?.cancel()
        endHold(timer.heldSec(System.currentTimeMillis())?.takeIf { it > 0 })
    }

    fun cancelHold() {
        holdJob?.cancel()
        endHold(null)
    }

    private fun endHold(seconds: Int?) {
        val (slot, input) = holdSet ?: return
        _hold.value = null
        holdSet = null
        holdJob = null
        if (seconds != null) logSet(slot, input.copy(seconds = seconds))
    }

    private suspend fun sleepUntil(at: Long) {
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
    }

    fun setRir(set: SetLogEntity, rir: Int) {
        viewModelScope.launch { workouts.setRir(set.id, if (set.rir == rir) null else rir) }
    }

    fun updateSet(set: SetLogEntity) {
        viewModelScope.launch { workouts.updateSet(set) }
    }

    fun deleteSet(set: SetLogEntity) {
        viewModelScope.launch { workouts.deleteSet(set.id) }
    }

    fun select(position: Int) {
        val ready = load.value as? WorkoutLoad.Ready ?: return
        if (ready.detail.session.currentPosition == position) return
        viewModelScope.launch { workouts.setPosition(sessionId, position) }
    }

    fun adjustRest(deltaSec: Int) {
        viewModelScope.launch { workouts.adjustRest(sessionId, deltaSec) }
    }

    fun skipRest() {
        viewModelScope.launch { workouts.skipRest(sessionId) }
    }

    fun clearExpiredRest() {
        viewModelScope.launch { workouts.clearExpiredRest(sessionId) }
    }

    fun openBusy(slot: SlotDetail) {
        val detail = (load.value as? WorkoutLoad.Ready)?.detail ?: return
        viewModelScope.launch {
            val infos = catalog.exerciseInfos()
            val available = profiles.current()?.inventory?.available
            val config = catalog.substitutionConfig.let { c ->
                if (available == null) c else c.copy(unavailableEquipment = infos.values.flatMap { it.equipment }.toSet() - available)
            }
            val original = infos[slot.exercise.id] ?: return@launch
            val pool = (original.substitutes + original.substitutes.flatMap { infos[it]?.substitutes.orEmpty() }).distinct()
            val request = SubstituteRequest(
                original = original,
                repMin = slot.slot.repMin,
                repMax = slot.slot.repMax,
                remainingSets = (slot.slot.sets - slot.workSetCount).coerceAtLeast(0),
                reference = referenceSet(slot),
                otherSlots = detail.slots.filter { it.slot.id != slot.slot.id }.map { SessionSlotInfo(it.exercise.id, it.slot.sets) },
                ownLastWeight = workouts.lastWorkWeights(pool, sessionId),
            )
            val options = Substitution.options(request, infos, config)
            val queue = detail.slots.map { QueueSlot(it.slot.id, it.exercise.id, it.slot.supersetGroup, it.isDone) }
            val wait = BusyQueue.whileWaiting(queue, slot.slot.id, infos, config)
            val entities = catalog.exercises(options.map { it.exerciseId } + wait.extras)
            _busy.value = BusySheet(
                slot = slot,
                options = options.mapNotNull { o -> entities[o.exerciseId]?.let { BusyOption(it, o) } },
                waitSlot = wait.slotId?.let { id -> detail.slots.firstOrNull { it.slot.id == id } },
                extras = wait.extras.mapNotNull { entities[it] },
                canReplaceForever = slot.slot.plannedExerciseId != null,
            )
        }
    }

    /** Latest working set today, otherwise the heaviest one from last time. */
    private fun referenceSet(slot: SlotDetail): ReferenceSet? {
        val today = slot.sets.lastOrNull { SetKind.fromCode(it.kind).countsAsWork && (it.weightKg ?: 0.0) > 0.0 }
        val source = today ?: _lastResults.value[slot.exercise.id].orEmpty()
            .filter { SetKind.fromCode(it.kind).countsAsWork && (it.weightKg ?: 0.0) > 0.0 }
            .maxByOrNull { it.weightKg ?: 0.0 }
        return source?.let { ReferenceSet(it.weightKg ?: 0.0, it.reps) }
    }

    fun closeBusy() {
        _busy.value = null
    }

    fun busyLater() {
        val sheet = _busy.value ?: return
        _busy.value = null
        viewModelScope.launch { workouts.moveToEnd(sessionId, sheet.slot.slot.id) }
    }

    fun busyReplace(option: BusyOption, forever: Boolean) {
        val sheet = _busy.value ?: return
        _busy.value = null
        val slotId = sheet.slot.slot.id
        _inputs.update { it - slotId }
        val estimate = option.option.estimate
        viewModelScope.launch {
            workouts.replaceExercise(
                sessionId = sessionId,
                slotId = slotId,
                newExerciseId = option.exercise.id,
                repMin = estimate.repMin,
                repMax = estimate.repMax,
                targetWeightKg = estimate.weightKg,
                forever = forever && sheet.canReplaceForever,
            )
        }
    }

    fun busyWaitWith(other: SlotDetail) {
        val sheet = _busy.value ?: return
        _busy.value = null
        viewModelScope.launch { workouts.waitWith(sessionId, sheet.slot.slot.id, other.slot.id) }
    }

    fun busyAddFiller(exercise: ExerciseEntity) {
        val sheet = _busy.value ?: return
        _busy.value = null
        viewModelScope.launch { workouts.addFiller(sessionId, sheet.slot.slot.id, exercise) }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            workouts.finish(sessionId)
            onDone()
        }
    }

    fun discard(onDone: () -> Unit) {
        viewModelScope.launch {
            workouts.discard(sessionId)
            onDone()
        }
    }

    private companion object {
        const val DOUBLE_TAP_MS = 600L
    }
}

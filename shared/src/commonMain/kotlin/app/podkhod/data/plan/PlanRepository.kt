package app.podkhod.data.plan

import app.podkhod.platform.nowMillis
import app.podkhod.data.db.withTransaction
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.db.PlannedExerciseEntity
import app.podkhod.data.db.ProgramDayWithExercises
import app.podkhod.data.db.ProgramEntity
import app.podkhod.data.db.ProgressionStateEntity
import app.podkhod.data.db.SessionExerciseEntity
import app.podkhod.data.db.SessionExerciseWithSets
import app.podkhod.data.db.WorkoutSessionEntity
import app.podkhod.data.profile.ProfileRepository
import app.podkhod.data.settings.SettingsRepository
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.plan.Change
import app.podkhod.domain.plan.Checkin
import app.podkhod.domain.plan.DeloadAdvisor
import app.podkhod.domain.plan.GeneratedProgram
import app.podkhod.domain.plan.GeneratorConfig
import app.podkhod.domain.plan.LockedSlot
import app.podkhod.domain.plan.LoggedSet
import app.podkhod.domain.plan.PlannedSlot
import app.podkhod.domain.plan.Profile
import app.podkhod.domain.plan.ProgramDiff
import app.podkhod.domain.plan.ProgramGenerator
import app.podkhod.domain.plan.Progression
import app.podkhod.domain.plan.ProgressionState
import app.podkhod.domain.plan.SessionContext
import app.podkhod.domain.plan.SessionPlanner
import app.podkhod.domain.plan.SlotRole
import app.podkhod.domain.plan.StartingWeights
import app.podkhod.domain.plan.StrengthData
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.LoadScheme
import app.podkhod.domain.weights.Loads
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.todayIn
import kotlin.time.Clock

@Serializable
data class VolumeDto(
    val group: String,
    val sets: Double,
    val min: Int,
    val max: Int,
    val targetLow: Int,
    val targetHigh: Int,
    val priority: Boolean,
) {
    val withinTarget: Boolean get() = sets + 1e-9 >= targetLow && sets - 1e-9 <= max
}

/** Program-level "Почему так?" stored with the program. */
@Serializable
data class ProgramExplanation(
    val notes: List<String> = emptyList(),
    val shortfalls: List<String> = emptyList(),
    val volume: List<VolumeDto> = emptyList(),
    val minutes: List<Int> = emptyList(),
)

@Serializable
data class ProgressLine(val exerciseId: String, val name: String, val change: String, val message: String)

data class ProgramPreview(
    val program: GeneratedProgram,
    /** Lines for the confirmation dialog; empty for the first program. */
    val changes: List<String>,
    val replacesProgram: Boolean,
)

/** Today's adjusted plan for one program day. */
data class StartPlan(
    val slots: List<Pair<PlannedExerciseEntity, PlannedSlot>>,
    val deload: Boolean,
    val notes: List<String>,
    val weekIndex: Int?,
)

class PlanRepository(
    private val db: AppDatabase,
    private val catalog: CatalogRepository,
    private val profiles: ProfileRepository,
    private val configs: GeneratorConfigStore,
    private val settings: SettingsRepository,
    private val clock: () -> Long = ::nowMillis,
    /** ISO weekday 1..7 of today. */
    private val weekday: () -> Int = { Clock.System.todayIn(TimeZone.currentSystemDefault()).dayOfWeek.isoDayNumber },
) {
    private val programDao = db.programDao()
    private val workoutDao = db.workoutDao()
    private val profileDao = db.profileDao()

    suspend fun config(): GeneratorConfig = configs.config()

    suspend fun names(): Map<String, String> = catalog.exerciseInfos().mapValues { it.value.name }

    /**
     * Generates a program for [profile] without saving it. With [nextMesocycle] the variations rotate;
     * otherwise the current mesocycle index is kept so a profile edit does not reshuffle exercises.
     */
    suspend fun preview(profile: Profile, nextMesocycle: Boolean = false): ProgramPreview {
        val config = configs.config()
        val infos = catalog.exerciseInfos()
        val active = programDao.active()
        val oldDays = active?.let { programDao.days(it.id) }.orEmpty().sortedBy { it.day.seqIndex }
        val generated = active?.source == SOURCE_GENERATED
        val mesocycle = if (generated) active!!.mesocycleIndex + if (nextMesocycle) 1 else 0 else 0
        val locked = if (generated) {
            oldDays.flatMapIndexed { i, d -> d.exercises.filter { it.locked }.map { LockedSlot(i, it.toSlot(infos[it.exerciseId])) } }
        } else {
            emptyList()
        }
        val states = profileDao.progressionStates().associateBy { it.exerciseId }
        val strength = StrengthData(
            ratios = catalog.substitutionConfig().ratios,
            historyE1rm = states.mapNotNull { (id, s) -> decodeHistory(s.e1rmHistoryJson).lastOrNull()?.let { id to it } }.toMap(),
            workingWeights = states.mapNotNull { (id, s) -> s.workingWeightKg?.let { id to it } }.toMap(),
        )
        val program = ProgramGenerator(config.withMinSets(settings.current().minSets)).generate(
            ProgramGenerator.Input(
                profile = profile,
                catalog = infos.values.toList(),
                strength = strength,
                busyCounts = workoutDao.busyCounts().associate { it.exerciseId to it.count },
                locked = locked,
                mesocycleIndex = mesocycle,
                accessoryWarmupSets = settings.current().accessoryWarmupSets,
            ),
        )
        val names = infos.mapValues { it.value.name }
        val changes = if (active == null) {
            emptyList()
        } else {
            ProgramDiff.diff(
                oldDays.map { d -> ProgramDiff.Day(d.day.title, d.exercises.sortedBy { it.position }.map { it.toSlot(infos[it.exerciseId]) }) },
                program.days.map { ProgramDiff.Day(it.title, it.slots) },
                names,
            )
        }
        return ProgramPreview(program, changes, active != null)
    }

    /**
     * Saves the generated program as the active one. A regeneration inside the mesocycle keeps the week and
     * the next day; [newMesocycle] starts from week 1, day 1.
     */
    suspend fun apply(preview: ProgramPreview, profile: Profile, newMesocycle: Boolean = false): Long = db.withTransaction {
        val p = preview.program
        val active = programDao.active()
        val keep = active != null && active.source == SOURCE_GENERATED && !newMesocycle
        val dayCount = p.days.size
        val explanation = ProgramExplanation(
            notes = p.notes,
            shortfalls = p.shortfalls,
            volume = p.volume.map { VolumeDto(it.group.code, it.sets, it.min, it.max, it.targetLow, it.targetHigh, it.priority) },
            minutes = p.days.map { it.estimatedMinutes },
        )
        programDao.insertProgramTree(
            ProgramEntity(
                name = p.name,
                source = SOURCE_GENERATED,
                templateId = null,
                status = STATUS_ACTIVE,
                createdAt = clock(),
                nextDayIndex = if (keep) active!!.nextDayIndex.mod(dayCount) else 0,
                weeks = p.weeks,
                weekIndex = if (keep) active!!.weekIndex.coerceAtMost(p.weeks) else 0,
                mesocycleIndex = p.mesocycleIndex,
                style = profile.style.code,
                explanationJson = JSON.encodeToString(ProgramExplanation.serializer(), explanation),
                deloadOffer = if (keep) active!!.deloadOffer else null,
                deloadSessionsLeft = if (keep) active!!.deloadSessionsLeft.coerceAtMost(dayCount) else 0,
            ),
            p.days.map { d -> d.title to d.slots.map { it.toEntity() } },
        )
    }

    suspend fun startNewMesocycle(): Long? {
        val profile = profiles.current() ?: return null
        return apply(preview(profile, nextMesocycle = true), profile, newMesocycle = true)
    }

    // --- manual edits -------------------------------------------------------------------------------

    suspend fun setLocked(plannedId: Long, locked: Boolean) {
        val row = programDao.planned(plannedId) ?: return
        programDao.updatePlanned(row.copy(locked = locked))
    }

    /**
     * Raises every exercise of the active program to the lifter's minimum of sets, once per change of the
     * setting (and once after an update that introduced it). Lowering the minimum leaves the program alone,
     * and a later manual «−» on one exercise is respected.
     */
    suspend fun applyMinSets() {
        val min = settings.current().minSets
        if (settings.appliedMinSets() == min) return
        db.withTransaction {
            val program = programDao.active() ?: return@withTransaction
            programDao.days(program.id).flatMap { it.exercises }.filter { it.sets < min }.forEach { row ->
                programDao.updatePlanned(row.copy(sets = min, explanationJson = addReason(row.explanationJson, "Минимум подходов из настроек: ${row.sets} → $min.")))
            }
        }
        settings.setAppliedMinSets(min)
    }

    suspend fun changeSets(plannedId: Long, delta: Int) {
        val row = programDao.planned(plannedId) ?: return
        val sets = (row.sets + delta).coerceIn(1, MAX_MANUAL_SETS)
        if (sets == row.sets) return
        programDao.updatePlanned(row.copy(sets = sets, explanationJson = addReason(row.explanationJson, "Подходы изменены тобой: ${row.sets} → $sets.")))
    }

    /** Replaces the exercise in the program; the rep range follows the new exercise, the weight is re-estimated. */
    suspend fun replacePlanned(plannedId: Long, newExerciseId: String) {
        val row = programDao.planned(plannedId) ?: return
        val infos = catalog.exerciseInfos()
        val info = infos[newExerciseId] ?: return
        val profile = profiles.current() ?: Profile()
        val config = configs.config()
        val repMin = maxOf(row.repMin, info.repMin).takeIf { it <= minOf(row.repMax, info.repMax) } ?: info.repMin
        val repMax = minOf(row.repMax, info.repMax).takeIf { it >= repMin } ?: info.repMax
        val states = profileDao.progressionStates(listOf(newExerciseId)).associateBy { it.exerciseId }
        val pick = StartingWeights(
            config,
            StrengthData(
                baselines = profile.baselines,
                bodyMassKg = profile.bodyMassKg,
                ratios = catalog.substitutionConfig().ratios,
                historyE1rm = states.mapNotNull { (id, s) -> decodeHistory(s.e1rmHistoryJson).lastOrNull()?.let { id to it } }.toMap(),
                workingWeights = states.mapNotNull { (id, s) -> s.workingWeightKg?.let { id to it } }.toMap(),
            ),
            infos.mapValues { it.value.name },
        ).pick(info, Loads.scheme(info, profile.inventory), repMin, row.rirMax ?: DEFAULT_RIR_MAX)
        val reasons = listOfNotNull("Заменено тобой: ${infos[row.exerciseId]?.name ?: row.exerciseId} → ${info.name}.", pick.reason)
        programDao.updatePlanned(
            row.copy(
                exerciseId = newExerciseId,
                repMin = repMin,
                repMax = repMax,
                targetWeightKg = pick.weightKg,
                marker = false,
                explanationJson = encodeReasons(reasons),
            ),
        )
    }

    // --- sessions -----------------------------------------------------------------------------------

    /** Today's plan for [day]: deload week, accepted unplanned deload, check-in and endurance adjustments. */
    suspend fun planFor(day: ProgramDayWithExercises, checkin: Checkin?): StartPlan {
        val program = programDao.program(day.day.programId)
        val infos = catalog.exerciseInfos()
        val profile = profiles.current()
        val rows = day.exercises.sortedBy { it.position }
        val generated = program?.source == SOURCE_GENERATED
        val ctx = SessionContext(
            weekIndex = if (generated) program!!.weekIndex else 0,
            weeks = if (generated) program!!.weeks else Int.MAX_VALUE,
            unplannedDeload = (program?.deloadSessionsLeft ?: 0) > 0,
            checkin = checkin,
            longEffortTomorrow = profile != null && (weekday() % 7 + 1) in profile.longEffortDays,
        )
        val plan = SessionPlanner(configs.config()).plan(
            rows.map { it.toSlot(infos[it.exerciseId]) },
            infos,
            profile?.inventory ?: Inventory(),
            ctx,
        )
        return StartPlan(rows.zip(plan.slots), plan.deload, plan.notes, if (generated) program!!.weekIndex else null)
    }

    /**
     * Applies double progression to every planned slot of a finished session, moves the week on after the
     * last day of the rotation and refreshes the unplanned-deload offer. Runs inside the finish transaction.
     */
    suspend fun afterFinish(session: WorkoutSessionEntity) {
        val ctx = progressContext()
        val config = ctx.config
        val infos = ctx.infos
        val slots = workoutDao.slots(session.id).sortedBy { it.slot.position }
        val ids = slots.map { it.slot.exerciseId }.distinct()
        val states = profileDao.progressionStates(ids).associateBy({ it.exerciseId }, { it.toDomain() }).toMutableMap()
        val program = session.programId?.let { programDao.program(it) }
        val activeProgram = program?.takeIf { it.status == STATUS_ACTIVE }
        val roles = rolesOf(program)
        val changed = mutableSetOf<String>()
        val lines = progressSession(session, slots, states, ctx, roles, changed)
        saveStates(changed, states, activeProgram, ctx)
        workoutDao.setProgress(session.id, encodeProgress(lines))

        if (activeProgram == null || activeProgram.source != SOURCE_GENERATED) return
        val dayCount = programDao.days(activeProgram.id).size
        var weekIndex = activeProgram.weekIndex
        if (dayCount > 0 && session.dayIndex == dayCount - 1 && weekIndex < activeProgram.weeks) {
            weekIndex += 1
            programDao.setWeek(activeProgram.id, weekIndex)
        }
        if (session.deload && activeProgram.deloadSessionsLeft > 0) {
            programDao.setDeloadSessionsLeft(activeProgram.id, activeProgram.deloadSessionsLeft - 1)
            programDao.setDeloadOffer(activeProgram.id, null)
            return
        }
        val plannedDeloadAhead = weekIndex >= activeProgram.weeks - 1
        val compounds = roles.filterValues { it == SlotRole.COMPOUND.code }.keys
        val compoundIds = programDao.days(activeProgram.id).flatMap { it.exercises }.filter { it.id in compounds }.map { it.exerciseId }.toSet()
        val histories = profileDao.progressionStates(compoundIds.toList()).associate { it.exerciseId to decodeHistory(it.e1rmHistoryJson) }
        val hard = workoutDao.recentFinished(activeProgram.id, config.deload.hardCheckins).reversed().map { it.checkin()?.isHard(config) == true }
        val offer = if (plannedDeloadAhead) null else DeloadAdvisor(config).advise(histories, hard, infos.mapValues { it.value.name })
        programDao.setDeloadOffer(activeProgram.id, offer)
    }

    /**
     * Replays double progression of [exerciseIds] over every finished session, oldest first, after a past
     * session was edited or deleted. Progression is a chain: one wrong set changes every later weight, so
     * the state is rebuilt from scratch rather than patched. Progress lines of sessions from
     * [fromStartedAt] on are rewritten; lines of other exercises stay as they were.
     */
    suspend fun recomputeProgression(exerciseIds: Set<String>, fromStartedAt: Long) {
        if (exerciseIds.isEmpty()) return
        val ctx = progressContext()
        val ids = exerciseIds.toList()
        val states = mutableMapOf<String, ProgressionState>()
        val roleCache = mutableMapOf<Long, Map<Long, String?>>()
        workoutDao.finishedWith(ids).forEach { session ->
            val all = workoutDao.slots(session.id).sortedBy { it.slot.position }
            val roles = session.programId?.let { id -> roleCache.getOrPut(id) { rolesOf(programDao.program(id)) } }.orEmpty()
            val lines = progressSession(session, all.filter { it.slot.exerciseId in exerciseIds }, states, ctx, roles, mutableSetOf())
            if (session.startedAt >= fromStartedAt) {
                val order = all.mapIndexed { i, s -> s.slot.exerciseId to i }.reversed().toMap()
                val merged = (decodeProgress(session.progressJson).filter { it.exerciseId !in exerciseIds } + lines)
                    .sortedBy { order[it.exerciseId] ?: Int.MAX_VALUE }
                workoutDao.setProgress(session.id, encodeProgress(merged))
            }
        }
        profileDao.deleteProgression(ids.filter { it !in states })
        saveStates(states.keys, states, programDao.active(), ctx)
    }

    private class ProgressContext(val config: GeneratorConfig, val infos: Map<String, ExerciseInfo>, val inventory: Inventory) {
        val progression = Progression(config)
    }

    private suspend fun progressContext() =
        ProgressContext(configs.config(), catalog.exerciseInfos(), profiles.current()?.inventory ?: Inventory())

    /** Slot roles by planned exercise id; archived programs keep their days, so old sessions still resolve. */
    private suspend fun rolesOf(program: ProgramEntity?): Map<Long, String?> =
        program?.let { p -> programDao.days(p.id).flatMap { it.exercises } }.orEmpty().associate { it.id to it.role }

    /**
     * Applies double progression to the planned slots of one session in order, updating [states] and
     * collecting the exercises whose state changed into [changed].
     */
    private fun progressSession(
        session: WorkoutSessionEntity,
        slots: List<SessionExerciseWithSets>,
        states: MutableMap<String, ProgressionState>,
        ctx: ProgressContext,
        roles: Map<Long, String?>,
        changed: MutableSet<String>,
    ): List<ProgressLine> {
        val lines = mutableListOf<ProgressLine>()
        slots.forEach { s ->
            val info = ctx.infos[s.slot.exerciseId] ?: return@forEach
            val plannedId = s.slot.plannedExerciseId ?: return@forEach
            val work = s.sets.filter { SetKind.fromCode(it.kind).countsAsWork }
                .sortedBy { it.position }
                .map { LoggedSet(it.weightKg ?: 0.0, it.reps, it.durationSec, it.rir) }
            if (work.isEmpty()) return@forEach
            val scheme = Loads.scheme(info, ctx.inventory)
            val slot = s.slot.toSlot(info, roles[plannedId])
            val before = states[info.id] ?: ProgressionState(s.slot.targetWeightKg)
            val r = ctx.progression.update(info, scheme, slot, work, before, session.deload)
            if (r.change == Change.NONE) return@forEach
            states[info.id] = r.state
            changed += info.id
            lines += ProgressLine(info.id, info.name, r.change.name.lowercase(), r.message)
        }
        return lines
    }

    /** Persists the states of [ids] and moves the active program's target weights to them. */
    private suspend fun saveStates(ids: Collection<String>, states: Map<String, ProgressionState>, activeProgram: ProgramEntity?, ctx: ProgressContext) {
        val now = clock()
        val rows = ids.mapNotNull { id -> states[id]?.toEntity(id, now) }
        if (rows.isEmpty()) return
        profileDao.upsertProgression(rows)
        rows.forEach { row ->
            val info = ctx.infos[row.exerciseId] ?: return@forEach
            val w = row.workingWeightKg
            if (activeProgram != null && w != null && Loads.scheme(info, ctx.inventory) !is LoadScheme.NoLoad) {
                programDao.setTargetWeight(activeProgram.id, info.id, w)
            }
        }
    }

    suspend fun acceptDeload() {
        val program = programDao.active() ?: return
        programDao.setDeloadSessionsLeft(program.id, programDao.days(program.id).size)
        programDao.setDeloadOffer(program.id, null)
    }

    suspend fun declineDeload() {
        val program = programDao.active() ?: return
        programDao.setDeloadOffer(program.id, null)
    }

    // --- mapping ------------------------------------------------------------------------------------

    private fun PlannedExerciseEntity.toSlot(info: ExerciseInfo?): PlannedSlot = PlannedSlot(
        exerciseId = exerciseId,
        role = roleOf(role, info),
        sets = sets,
        repMin = repMin,
        repMax = repMax,
        rirMin = rirMin ?: DEFAULT_RIR_MIN,
        rirMax = rirMax ?: rirMin ?: DEFAULT_RIR_MAX,
        restSec = restSec,
        targetWeightKg = targetWeightKg,
        supersetGroup = supersetGroup,
        locked = locked,
        marker = marker,
        note = note,
        reasons = decodeReasons(explanationJson),
    )

    private fun SessionExerciseEntity.toSlot(info: ExerciseInfo, role: String?): PlannedSlot = PlannedSlot(
        exerciseId = exerciseId,
        role = roleOf(role, info),
        sets = sets,
        repMin = repMin,
        repMax = repMax,
        // Unrated reserve never blocks progression.
        rirMin = rirMin ?: 0,
        rirMax = rirMax ?: rirMin ?: 0,
        restSec = restSec,
        targetWeightKg = targetWeightKg,
        supersetGroup = supersetGroup,
    )

    private fun PlannedSlot.toEntity() = PlannedExerciseEntity(
        programDayId = 0,
        position = 0,
        exerciseId = exerciseId,
        supersetGroup = supersetGroup,
        sets = sets,
        repMin = repMin,
        repMax = repMax,
        rirMin = rirMin,
        rirMax = rirMax,
        restSec = restSec,
        targetWeightKg = targetWeightKg,
        note = note,
        locked = locked,
        role = role.code,
        marker = marker,
        explanationJson = encodeReasons(reasons),
    )

    private fun roleOf(code: String?, info: ExerciseInfo?): SlotRole = code?.let(SlotRole::fromCode) ?: when (info?.kind) {
        ExerciseKind.COMPOUND -> SlotRole.COMPOUND
        ExerciseKind.ISOLATION -> SlotRole.ISOLATION
        else -> SlotRole.ACCESSORY
    }

    private fun ProgressionStateEntity.toDomain() = ProgressionState(workingWeightKg, missesInRow, decodeHistory(e1rmHistoryJson), note)

    private fun ProgressionState.toEntity(exerciseId: String, now: Long) = ProgressionStateEntity(
        exerciseId = exerciseId,
        workingWeightKg = workingWeightKg,
        missesInRow = missesInRow,
        e1rmHistoryJson = JSON.encodeToString(DOUBLES, e1rmHistory),
        note = note,
        updatedAt = now,
    )

    private fun WorkoutSessionEntity.checkin(): Checkin? {
        if (checkinSleep == null && checkinLegs == null && checkinReady == null) return null
        return Checkin(checkinSleep ?: 0, checkinLegs ?: 0, checkinReady ?: 0, checkinLong == true)
    }

    companion object {
        const val SOURCE_GENERATED = "generated"
        const val STATUS_ACTIVE = "active"
        const val MAX_MANUAL_SETS = 8
        private const val DEFAULT_RIR_MIN = 1
        private const val DEFAULT_RIR_MAX = 2

        val JSON = Json { ignoreUnknownKeys = true }
        private val STRINGS = ListSerializer(String.serializer())
        private val DOUBLES = ListSerializer(Double.serializer())

        fun decodeReasons(json: String?): List<String> =
            json?.let { runCatching { JSON.decodeFromString(STRINGS, it) }.getOrNull() }.orEmpty()

        fun encodeReasons(reasons: List<String>): String? = if (reasons.isEmpty()) null else JSON.encodeToString(STRINGS, reasons)

        fun decodeHistory(json: String): List<Double> = runCatching { JSON.decodeFromString(DOUBLES, json) }.getOrDefault(emptyList())

        fun decodeExplanation(json: String?): ProgramExplanation? =
            json?.let { runCatching { JSON.decodeFromString(ProgramExplanation.serializer(), it) }.getOrNull() }

        fun encodeProgress(lines: List<ProgressLine>): String? =
            if (lines.isEmpty()) null else JSON.encodeToString(ListSerializer(ProgressLine.serializer()), lines)

        fun decodeProgress(json: String?): List<ProgressLine> =
            json?.let { runCatching { JSON.decodeFromString(ListSerializer(ProgressLine.serializer()), it) }.getOrNull() }.orEmpty()

        private fun addReason(json: String?, line: String): String? = encodeReasons(decodeReasons(json) + line)
    }
}

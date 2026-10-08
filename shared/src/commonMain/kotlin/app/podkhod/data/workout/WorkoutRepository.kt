package app.podkhod.data.workout

import app.podkhod.platform.nowMillis
import app.podkhod.data.db.withTransaction
import app.podkhod.data.catalog.CatalogRepository
import app.podkhod.data.db.AppDatabase
import app.podkhod.data.db.BusyCountRow
import app.podkhod.data.db.BusyEventEntity
import app.podkhod.data.db.ExerciseEntity
import app.podkhod.data.db.HistoryRow
import app.podkhod.data.db.SessionExerciseEntity
import app.podkhod.data.db.SessionExerciseWithSets
import app.podkhod.data.db.SetLogEntity
import app.podkhod.data.db.WorkoutSessionEntity
import app.podkhod.data.plan.PlanRepository
import app.podkhod.data.program.ProgramRepository
import app.podkhod.domain.plan.Checkin
import app.podkhod.domain.busy.BusyQueue
import app.podkhod.domain.busy.QueueSlot
import app.podkhod.domain.model.SetKind
import app.podkhod.domain.workout.ExerciseRecord
import app.podkhod.domain.workout.LoggedSet
import app.podkhod.domain.workout.SessionStats
import app.podkhod.domain.workout.SessionSummary
import app.podkhod.domain.workout.SlotState
import app.podkhod.domain.workout.SupersetRestMode
import app.podkhod.domain.workout.WorkoutFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class SlotDetail(
    val slot: SessionExerciseEntity,
    val exercise: ExerciseEntity,
    val sets: List<SetLogEntity>,
) {
    val workSetCount: Int get() = sets.count { SetKind.fromCode(it.kind).countsAsWork }
    val isDone: Boolean get() = workSetCount >= slot.sets
}

data class SessionDetail(
    val session: WorkoutSessionEntity,
    val slots: List<SlotDetail>,
) {
    val plannedWorkSets: Int get() = slots.sumOf { it.slot.sets }
    val doneWorkSets: Int get() = slots.sumOf { minOf(it.workSetCount, it.slot.sets) }
    val remainingWorkSets: Int get() = (plannedWorkSets - doneWorkSets).coerceAtLeast(0)

    /** Index of the slot «Дальше» leads to from [index], or null when everything else is done. */
    fun nextIndexAfter(index: Int): Int? {
        val states = slots.map { SlotState(it.slot.position, it.slot.supersetGroup, it.slot.sets, it.workSetCount, it.slot.restSec) }
        val position = WorkoutFlow.nextUnfinished(states, slots.getOrNull(index)?.slot?.position ?: return null) ?: return null
        return slots.indexOfFirst { it.slot.position == position }.takeIf { it >= 0 }
    }

    fun loggedSets(): List<LoggedSet> = slots.flatMap { s ->
        s.sets.map { LoggedSet(s.exercise.id, SetKind.fromCode(it.kind), it.weightKg, it.reps, it.durationSec) }
    }
}

data class SessionResult(
    val summary: SessionSummary,
    val records: List<ExerciseRecord>,
    /** Volume of the previous finished session of the same program day, for comparison. */
    val previousVolumeKg: Double?,
)

class WorkoutRepository(
    private val db: AppDatabase,
    private val catalog: CatalogRepository,
    private val programs: ProgramRepository,
    private val plans: PlanRepository,
    private val clock: () -> Long = ::nowMillis,
) {
    private val dao = db.workoutDao()
    private val programDao = db.programDao()

    val activeSession: Flow<WorkoutSessionEntity?> = dao.observeActive()
    val history: Flow<List<HistoryRow>> = dao.observeHistory()

    suspend fun activeSessionNow(): WorkoutSessionEntity? = dao.active()

    fun observeDetail(sessionId: Long): Flow<SessionDetail?> =
        combine(dao.observeSession(sessionId), dao.observeSlots(sessionId), catalog.exercisesById) { session, slots, exercises ->
            if (session == null) return@combine null
            SessionDetail(
                session = session,
                slots = slots.mapNotNull { s ->
                    val ex = exercises[s.slot.exerciseId] ?: return@mapNotNull null
                    SlotDetail(s.slot, ex, s.sets.sortedBy { it.position })
                },
            )
        }

    /**
     * Starts a workout for a program day with today's plan (deload week, check-in, endurance). Only one
     * workout can be active: an existing one is returned instead.
     */
    suspend fun startFromProgramDay(programDayId: Long, checkin: Checkin? = null): Long = db.withTransaction {
        dao.active()?.let { return@withTransaction it.id }
        val day = requireNotNull(programDao.day(programDayId)) { "program day $programDayId not found" }
        val plan = plans.planFor(day, checkin)
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                programId = day.day.programId,
                programDayId = day.day.id,
                dayIndex = day.day.seqIndex,
                title = day.day.title,
                startedAt = clock(),
                finishedAt = null,
                status = WorkoutSessionEntity.ACTIVE,
                currentPosition = 0,
                restStartedAt = null,
                restEndsAt = null,
                feeling = null,
                note = null,
                weekIndex = plan.weekIndex,
                deload = plan.deload,
                checkinSleep = checkin?.sleep,
                checkinLegs = checkin?.legs,
                checkinReady = checkin?.readiness,
                checkinLong = checkin?.longEffortTomorrow,
                planNote = plan.notes.joinToString("\n").takeIf { it.isNotEmpty() },
            ),
        )
        dao.insertSlots(
            plan.slots.mapIndexed { index, (p, s) ->
                SessionExerciseEntity(
                    sessionId = sessionId,
                    position = index,
                    exerciseId = p.exerciseId,
                    plannedExerciseId = p.id,
                    supersetGroup = p.supersetGroup,
                    sets = s.sets,
                    repMin = s.repMin,
                    repMax = s.repMax,
                    // Template rows without a reserve stay without one.
                    rirMin = p.rirMin?.let { s.rirMin },
                    rirMax = p.rirMax?.let { s.rirMax },
                    restSec = s.restSec,
                    targetWeightKg = s.targetWeightKg,
                    note = p.note,
                )
            },
        )
        sessionId
    }

    /**
     * Persists a set, moves the session to the next slot and starts the rest timer in one transaction,
     * so a crash right after the tap never loses the set or leaves a half-updated session.
     */
    suspend fun logSet(
        sessionId: Long,
        slotId: Long,
        kind: SetKind,
        weightKg: Double?,
        reps: Int?,
        durationSec: Int?,
        restMode: SupersetRestMode,
    ): Long = db.withTransaction {
        val now = clock()
        val slots = dao.slots(sessionId)
        val target = slots.first { it.slot.id == slotId }
        val setId = dao.insertSet(
            SetLogEntity(
                sessionExerciseId = slotId,
                position = (target.sets.maxOfOrNull { it.position } ?: -1) + 1,
                kind = kind.code,
                weightKg = weightKg,
                reps = reps,
                durationSec = durationSec,
                rir = null,
                completedAt = now,
            ),
        )
        val states = slots.map { s ->
            val logged = s.sets.count { SetKind.fromCode(it.kind).countsAsWork } +
                if (s.slot.id == slotId && kind.countsAsWork) 1 else 0
            SlotState(s.slot.position, s.slot.supersetGroup, s.slot.sets, logged, s.slot.restSec)
        }
        val decision = WorkoutFlow.afterSet(states, target.slot.position, kind == SetKind.WARMUP, restMode)
        dao.setPosition(sessionId, decision.nextPosition)
        if (decision.restSec > 0) {
            dao.setRest(sessionId, now, now + decision.restSec * 1000L)
        } else {
            dao.setRest(sessionId, null, null)
        }
        setId
    }

    /**
     * Replaces the exercise of a slot. Logged sets belong to the exercise that was actually performed, so a
     * slot with sets is split: it keeps its sets and a new slot right after it takes the remaining ones.
     * With [forever] the program day is changed too, so the next time this day starts with the substitute.
     */
    suspend fun replaceExercise(
        sessionId: Long,
        slotId: Long,
        newExerciseId: String,
        repMin: Int,
        repMax: Int,
        targetWeightKg: Double?,
        forever: Boolean,
    ): Boolean = db.withTransaction {
        val slots = dao.slots(sessionId).sortedBy { it.slot.position }
        val target = slots.firstOrNull { it.slot.id == slotId } ?: return@withTransaction false
        val workDone = target.sets.count { SetKind.fromCode(it.kind).countsAsWork }
        val remaining = target.slot.sets - workDone
        if (remaining <= 0) return@withTransaction false

        if (target.sets.isEmpty()) {
            dao.replaceSlotExercise(slotId, newExerciseId, repMin, repMax, targetWeightKg)
        } else {
            dao.setSlotSets(slotId, workDone)
            slots.filter { it.slot.position > target.slot.position }
                .sortedByDescending { it.slot.position }
                .forEach { dao.setSlotPosition(it.slot.id, it.slot.position + 1) }
            dao.insertSlot(
                target.slot.copy(
                    id = 0,
                    position = target.slot.position + 1,
                    exerciseId = newExerciseId,
                    sets = remaining,
                    repMin = repMin,
                    repMax = repMax,
                    targetWeightKg = targetWeightKg,
                ),
            )
            dao.setPosition(sessionId, target.slot.position + 1)
        }

        if (forever) {
            target.slot.plannedExerciseId?.let { plannedId ->
                val planned = programDao.planned(plannedId)
                if (planned != null) {
                    val ex = catalog.exercises(listOf(planned.exerciseId, newExerciseId))
                    val keep = ex[planned.exerciseId]?.measure == ex[newExerciseId]?.measure
                    val replacement = ex[newExerciseId]
                    programDao.replacePlannedExercise(
                        id = plannedId,
                        exerciseId = newExerciseId,
                        repMin = if (keep || replacement == null) planned.repMin else replacement.repMin,
                        repMax = if (keep || replacement == null) planned.repMax else replacement.repMax,
                    )
                }
            }
        }
        logBusy(sessionId, target.slot.exerciseId, if (forever) BusyEventEntity.REPLACE_PROGRAM else BusyEventEntity.REPLACE_TODAY, newExerciseId)
        true
    }

    /** "Do it later": the slot and its superset partners go to the end of the queue. */
    suspend fun moveToEnd(sessionId: Long, slotId: Long) = db.withTransaction {
        val slots = dao.slots(sessionId).sortedBy { it.slot.position }
        val target = slots.firstOrNull { it.slot.id == slotId } ?: return@withTransaction
        val reorder = BusyQueue.moveToEnd(slots.map { it.toQueueSlot() }, slotId)
        reorder.order.forEachIndexed { index, id -> dao.setSlotPosition(id, index) }
        dao.setPosition(sessionId, reorder.order.indexOf(reorder.nextId))
        logBusy(sessionId, target.slot.exerciseId, BusyEventEntity.LATER, null)
    }

    /** Jumps to another slot while the station is taken; the queue order stays as is. */
    suspend fun waitWith(sessionId: Long, busySlotId: Long, otherSlotId: Long) = db.withTransaction {
        val slots = dao.slots(sessionId)
        val busy = slots.firstOrNull { it.slot.id == busySlotId } ?: return@withTransaction
        val other = slots.firstOrNull { it.slot.id == otherSlotId } ?: return@withTransaction
        dao.setPosition(sessionId, other.slot.position)
        logBusy(sessionId, busy.slot.exerciseId, BusyEventEntity.WAIT, other.slot.exerciseId)
    }

    /** Adds a light filler exercise to the end of the session and opens it. */
    suspend fun addFiller(sessionId: Long, busySlotId: Long, exercise: ExerciseEntity) = db.withTransaction {
        val slots = dao.slots(sessionId)
        val busy = slots.firstOrNull { it.slot.id == busySlotId } ?: return@withTransaction
        val position = (slots.maxOfOrNull { it.slot.position } ?: -1) + 1
        dao.insertSlot(
            SessionExerciseEntity(
                sessionId = sessionId,
                position = position,
                exerciseId = exercise.id,
                plannedExerciseId = null,
                supersetGroup = null,
                sets = FILLER_SETS,
                repMin = exercise.repMin,
                repMax = exercise.repMax,
                rirMin = null,
                rirMax = null,
                restSec = exercise.restSec,
                targetWeightKg = null,
                note = null,
            ),
        )
        dao.setPosition(sessionId, position)
        logBusy(sessionId, busy.slot.exerciseId, BusyEventEntity.WAIT, exercise.id)
    }

    val busiest: Flow<List<BusyCountRow>> = dao.observeBusiest(BUSIEST_LIMIT)

    suspend fun busyEvents(sessionId: Long): List<BusyEventEntity> = dao.busyEvents(sessionId)

    /** Heaviest working weight of the last finished session per exercise, for substitute estimates. */
    suspend fun lastWorkWeights(exerciseIds: Collection<String>, excludeSessionId: Long): Map<String, Double> =
        exerciseIds.distinct().mapNotNull { id ->
            dao.lastResult(id, excludeSessionId)
                .filter { SetKind.fromCode(it.kind).countsAsWork }
                .mapNotNull { it.weightKg }
                .maxOrNull()
                ?.let { id to it }
        }.toMap()

    private suspend fun logBusy(sessionId: Long, exerciseId: String, action: String, replacementId: String?) {
        dao.insertBusyEvent(BusyEventEntity(sessionId = sessionId, exerciseId = exerciseId, action = action, replacementId = replacementId, createdAt = clock()))
    }

    private fun SessionExerciseWithSets.toQueueSlot() = QueueSlot(
        id = slot.id,
        exerciseId = slot.exerciseId,
        supersetGroup = slot.supersetGroup,
        done = sets.count { SetKind.fromCode(it.kind).countsAsWork } >= slot.sets,
    )

    suspend fun setRir(setId: Long, rir: Int?) {
        val set = dao.set(setId) ?: return
        dao.updateSet(set.copy(rir = rir))
    }

    suspend fun updateSet(set: SetLogEntity) = dao.updateSet(set)

    suspend fun deleteSet(setId: Long) = dao.deleteSet(setId)

    suspend fun setPosition(sessionId: Long, position: Int) = dao.setPosition(sessionId, position)

    suspend fun adjustRest(sessionId: Long, deltaSec: Int) {
        val session = dao.session(sessionId) ?: return
        val endsAt = session.restEndsAt ?: return
        val now = clock()
        val newEnd = WorkoutFlow.adjustRestEnd(endsAt, deltaSec, now)
        if (newEnd <= now) dao.setRest(sessionId, null, null) else dao.setRest(sessionId, session.restStartedAt, newEnd)
    }

    suspend fun skipRest(sessionId: Long) = dao.setRest(sessionId, null, null)

    /** Rest that ran out while nobody looked (screen off, process killed) is cleared on the next read. */
    suspend fun clearExpiredRest(sessionId: Long) {
        val session = dao.session(sessionId) ?: return
        val endsAt = session.restEndsAt ?: return
        if (endsAt <= clock()) dao.setRest(sessionId, null, null)
    }

    suspend fun finish(sessionId: Long) {
        val session = dao.session(sessionId) ?: return
        if (session.status == WorkoutSessionEntity.FINISHED) return
        db.withTransaction {
            dao.markFinished(sessionId, clock())
            plans.afterFinish(session)
            val programId = session.programId
            val dayIndex = session.dayIndex
            if (programId != null && dayIndex != null) programs.advanceAfterFinish(programId, dayIndex)
        }
    }

    suspend fun discard(sessionId: Long) = dao.deleteSession(sessionId)

    // --- editing finished sessions ------------------------------------------------------------------
    // A finished session fed double progression, so every edit replays progression of the touched exercises.

    suspend fun updateFinishedSet(set: SetLogEntity) = editFinished(set.sessionExerciseId) { dao.updateSet(set) }

    suspend fun deleteFinishedSet(set: SetLogEntity) = editFinished(set.sessionExerciseId) { dao.deleteSet(set.id) }

    suspend fun addFinishedSet(slotId: Long, kind: SetKind, weightKg: Double?, reps: Int?, durationSec: Int?) =
        editFinished(slotId) { session ->
            val sets = dao.slots(session.id).firstOrNull { it.slot.id == slotId }?.sets.orEmpty()
            dao.insertSet(
                SetLogEntity(
                    sessionExerciseId = slotId,
                    position = (sets.maxOfOrNull { it.position } ?: -1) + 1,
                    kind = kind.code,
                    weightKg = weightKg,
                    reps = reps,
                    durationSec = durationSec,
                    rir = null,
                    completedAt = sets.maxOfOrNull { it.completedAt } ?: session.finishedAt ?: session.startedAt,
                ),
            )
        }

    /** Deletes a finished session and replays progression without it. Week and day of the program stay. */
    suspend fun deleteFinished(sessionId: Long) = db.withTransaction {
        val session = dao.session(sessionId) ?: return@withTransaction
        val exercises = dao.slots(sessionId).map { it.slot.exerciseId }.toSet()
        dao.deleteSession(sessionId)
        if (session.status == WorkoutSessionEntity.FINISHED) plans.recomputeProgression(exercises, session.startedAt)
    }

    private suspend fun editFinished(slotId: Long, block: suspend (WorkoutSessionEntity) -> Unit) = db.withTransaction {
        val slot = dao.slot(slotId) ?: return@withTransaction
        val session = dao.session(slot.sessionId) ?: return@withTransaction
        block(session)
        if (session.status == WorkoutSessionEntity.FINISHED) plans.recomputeProgression(setOf(slot.exerciseId), session.startedAt)
    }

    suspend fun setFeedback(sessionId: Long, feeling: Int?, note: String?) =
        dao.setFeedback(sessionId, feeling, note?.trim()?.takeIf { it.isNotEmpty() })

    suspend fun lastResults(exerciseIds: Collection<String>, excludeSessionId: Long): Map<String, List<SetLogEntity>> =
        exerciseIds.distinct().associateWith { dao.lastResult(it, excludeSessionId) }

    suspend fun result(detail: SessionDetail): SessionResult {
        val session = detail.session
        val current = detail.loggedSets()
        val ids = detail.slots.map { it.exercise.id }
        val historyRows = if (ids.isEmpty()) emptyList() else dao.historySets(ids, session.startedAt)
        val history = historyRows.map { LoggedSet(it.exerciseId, SetKind.fromCode(it.kind), it.weightKg, it.reps, it.durationSec) }

        val previousVolume = session.programDayId?.let { dayId -> dao.previousForDay(dayId, session.startedAt)?.volumeKg }
        return SessionResult(
            summary = SessionStats.summarize(current, session.startedAt, session.finishedAt ?: clock()),
            records = SessionStats.records(current, history),
            previousVolumeKg = previousVolume,
        )
    }

    private companion object {
        const val FILLER_SETS = 2
        const val BUSIEST_LIMIT = 5
    }
}

package app.podkhod.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CatalogDao {
    @Upsert
    abstract suspend fun upsertExercises(items: List<ExerciseEntity>)

    @Query("DELETE FROM exercise_equipment WHERE exercise_id IN (:ids)")
    abstract suspend fun deleteEquipment(ids: List<String>)

    @Query("DELETE FROM exercise_muscle WHERE exercise_id IN (:ids)")
    abstract suspend fun deleteMuscles(ids: List<String>)

    @Query("DELETE FROM exercise_tag WHERE exercise_id IN (:ids)")
    abstract suspend fun deleteTags(ids: List<String>)

    @Query("DELETE FROM exercise_substitute WHERE exercise_id IN (:ids)")
    abstract suspend fun deleteSubstitutes(ids: List<String>)

    @Insert
    abstract suspend fun insertEquipment(items: List<ExerciseEquipmentEntity>)

    @Insert
    abstract suspend fun insertMuscles(items: List<ExerciseMuscleEntity>)

    @Insert
    abstract suspend fun insertTags(items: List<ExerciseTagEntity>)

    @Insert
    abstract suspend fun insertSubstitutes(items: List<ExerciseSubstituteEntity>)

    /** Exercises are upserted, never deleted: history rows keep pointing at them across catalog versions. */
    @Transaction
    open suspend fun replaceCatalog(
        exercises: List<ExerciseEntity>,
        equipment: List<ExerciseEquipmentEntity>,
        muscles: List<ExerciseMuscleEntity>,
        tags: List<ExerciseTagEntity>,
        substitutes: List<ExerciseSubstituteEntity>,
    ) {
        upsertExercises(exercises)
        val ids = exercises.map { it.id }
        deleteEquipment(ids)
        deleteMuscles(ids)
        deleteTags(ids)
        deleteSubstitutes(ids)
        insertEquipment(equipment)
        insertMuscles(muscles)
        insertTags(tags)
        insertSubstitutes(substitutes)
    }

    @Query("SELECT COUNT(*) FROM exercise")
    abstract suspend fun count(): Int

    @Query("SELECT id FROM exercise")
    abstract suspend fun ids(): List<String>

    @Query("SELECT * FROM exercise")
    abstract fun observeExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE id IN (:ids)")
    abstract suspend fun exercises(ids: List<String>): List<ExerciseEntity>

    @Query("SELECT * FROM exercise_muscle")
    abstract fun observeMuscles(): Flow<List<ExerciseMuscleEntity>>

    @Query("SELECT * FROM exercise_muscle WHERE exercise_id IN (:ids)")
    abstract suspend fun muscles(ids: List<String>): List<ExerciseMuscleEntity>

    @Query(
        "SELECT e.* FROM exercise_substitute s JOIN exercise e ON e.id = s.substitute_id " +
            "WHERE s.exercise_id = :exerciseId ORDER BY s.rank",
    )
    abstract suspend fun substitutes(exerciseId: String): List<ExerciseEntity>

    @Query("SELECT * FROM exercise")
    abstract suspend fun allExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM exercise_muscle")
    abstract suspend fun allMuscles(): List<ExerciseMuscleEntity>

    @Query("SELECT * FROM exercise_equipment")
    abstract suspend fun allEquipment(): List<ExerciseEquipmentEntity>

    @Query("SELECT * FROM exercise_substitute ORDER BY exercise_id, rank")
    abstract suspend fun allSubstitutes(): List<ExerciseSubstituteEntity>
}

@Dao
abstract class ProgramDao {
    @Insert
    abstract suspend fun insertProgram(program: ProgramEntity): Long

    @Insert
    abstract suspend fun insertDay(day: ProgramDayEntity): Long

    @Insert
    abstract suspend fun insertPlanned(items: List<PlannedExerciseEntity>)

    @Query("SELECT * FROM program WHERE status = 'active' ORDER BY id DESC LIMIT 1")
    abstract fun observeActive(): Flow<ProgramEntity?>

    @Query("SELECT * FROM program WHERE status = 'active' ORDER BY id DESC LIMIT 1")
    abstract suspend fun active(): ProgramEntity?

    @Transaction
    @Query("SELECT * FROM program_day WHERE program_id = :programId ORDER BY seq_index")
    abstract fun observeDays(programId: Long): Flow<List<ProgramDayWithExercises>>

    @Transaction
    @Query("SELECT * FROM program_day WHERE program_id = :programId ORDER BY seq_index")
    abstract suspend fun days(programId: Long): List<ProgramDayWithExercises>

    @Transaction
    @Query("SELECT * FROM program_day WHERE id = :dayId")
    abstract suspend fun day(dayId: Long): ProgramDayWithExercises?

    @Query("UPDATE program SET next_day_index = :index WHERE id = :programId")
    abstract suspend fun setNextDay(programId: Long, index: Int)

    @Query("SELECT * FROM planned_exercise WHERE id = :id")
    abstract suspend fun planned(id: Long): PlannedExerciseEntity?

    @Query(
        "UPDATE planned_exercise SET exercise_id = :exerciseId, rep_min = :repMin, rep_max = :repMax, " +
            "target_weight_kg = NULL WHERE id = :id",
    )
    abstract suspend fun replacePlannedExercise(id: Long, exerciseId: String, repMin: Int, repMax: Int)

    @Update
    abstract suspend fun updatePlanned(item: PlannedExerciseEntity)

    @Query("UPDATE program SET status = 'archived' WHERE status = 'active'")
    abstract suspend fun archiveActive()

    @Query("SELECT * FROM program WHERE id = :id")
    abstract suspend fun program(id: Long): ProgramEntity?

    @Query("UPDATE program SET week_index = :weekIndex WHERE id = :programId")
    abstract suspend fun setWeek(programId: Long, weekIndex: Int)

    @Query("UPDATE program SET deload_offer = :offer WHERE id = :programId")
    abstract suspend fun setDeloadOffer(programId: Long, offer: String?)

    @Query("UPDATE program SET deload_sessions_left = :left WHERE id = :programId")
    abstract suspend fun setDeloadSessionsLeft(programId: Long, left: Int)

    @Query(
        "UPDATE planned_exercise SET target_weight_kg = :weightKg WHERE exercise_id = :exerciseId " +
            "AND program_day_id IN (SELECT id FROM program_day WHERE program_id = :programId)",
    )
    abstract suspend fun setTargetWeight(programId: Long, exerciseId: String, weightKg: Double?)

    @Transaction
    open suspend fun insertProgramTree(program: ProgramEntity, days: List<Pair<String, List<PlannedExerciseEntity>>>): Long {
        archiveActive()
        val programId = insertProgram(program)
        days.forEachIndexed { index, (title, items) ->
            val dayId = insertDay(ProgramDayEntity(programId = programId, seqIndex = index, title = title))
            insertPlanned(items.mapIndexed { pos, p -> p.copy(id = 0, programDayId = dayId, position = pos) })
        }
        return programId
    }
}

@Dao
abstract class ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    abstract fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    abstract suspend fun profile(): ProfileEntity?

    @Upsert
    abstract suspend fun upsertProfile(profile: ProfileEntity)

    @Query("SELECT * FROM plate ORDER BY weight_kg DESC")
    abstract suspend fun plates(): List<PlateEntity>

    @Query("SELECT * FROM kettlebell ORDER BY weight_kg")
    abstract suspend fun kettlebells(): List<KettlebellEntity>

    @Query("SELECT * FROM dumbbell ORDER BY weight_kg")
    abstract suspend fun dumbbells(): List<DumbbellEntity>

    @Query("SELECT * FROM baseline_lift")
    abstract suspend fun baselines(): List<BaselineLiftEntity>

    @Query("DELETE FROM plate")
    abstract suspend fun clearPlates()

    @Query("DELETE FROM kettlebell")
    abstract suspend fun clearKettlebells()

    @Query("DELETE FROM dumbbell")
    abstract suspend fun clearDumbbells()

    @Query("DELETE FROM baseline_lift")
    abstract suspend fun clearBaselines()

    @Insert
    abstract suspend fun insertPlates(items: List<PlateEntity>)

    @Insert
    abstract suspend fun insertKettlebells(items: List<KettlebellEntity>)

    @Insert
    abstract suspend fun insertDumbbells(items: List<DumbbellEntity>)

    @Insert
    abstract suspend fun insertBaselines(items: List<BaselineLiftEntity>)

    /** The profile and its child lists are always saved together, so a half-written profile never exists. */
    @Transaction
    open suspend fun save(
        profile: ProfileEntity,
        plates: List<PlateEntity>,
        kettlebells: List<KettlebellEntity>,
        dumbbells: List<DumbbellEntity>,
        baselines: List<BaselineLiftEntity>,
    ) {
        upsertProfile(profile)
        clearPlates()
        clearKettlebells()
        clearDumbbells()
        clearBaselines()
        insertPlates(plates)
        insertKettlebells(kettlebells)
        insertDumbbells(dumbbells)
        insertBaselines(baselines)
    }

    @Query("SELECT * FROM progression_state")
    abstract suspend fun progressionStates(): List<ProgressionStateEntity>

    @Query("SELECT * FROM progression_state WHERE exercise_id IN (:ids)")
    abstract suspend fun progressionStates(ids: List<String>): List<ProgressionStateEntity>

    @Upsert
    abstract suspend fun upsertProgression(items: List<ProgressionStateEntity>)

    @Query("DELETE FROM progression_state WHERE exercise_id IN (:ids)")
    abstract suspend fun deleteProgression(ids: List<String>)
}

@Dao
abstract class WorkoutDao {
    @Insert
    abstract suspend fun insertSession(session: WorkoutSessionEntity): Long

    @Insert
    abstract suspend fun insertSlots(items: List<SessionExerciseEntity>)

    @Insert
    abstract suspend fun insertSet(set: SetLogEntity): Long

    @Update
    abstract suspend fun updateSet(set: SetLogEntity)

    @Query("DELETE FROM set_log WHERE id = :setId")
    abstract suspend fun deleteSet(setId: Long)

    @Query("SELECT * FROM set_log WHERE id = :setId")
    abstract suspend fun set(setId: Long): SetLogEntity?

    @Query("SELECT COUNT(*) FROM workout_session WHERE status = 'finished'")
    abstract suspend fun finishedCount(): Int

    @Query("SELECT * FROM session_exercise WHERE id = :slotId")
    abstract suspend fun slot(slotId: Long): SessionExerciseEntity?

    /** Finished sessions that contain any of [exerciseIds], oldest first: the order progression ran in. */
    @Query(
        """
        SELECT * FROM workout_session WHERE status = 'finished' AND id IN (
            SELECT session_id FROM session_exercise WHERE exercise_id IN (:exerciseIds)
        )
        ORDER BY started_at, id
        """,
    )
    abstract suspend fun finishedWith(exerciseIds: List<String>): List<WorkoutSessionEntity>

    /** Every logged set of finished sessions, for the progress screens. */
    @Query(
        """
        SELECT w.id AS session_id, w.started_at AS started_at, se.exercise_id AS exercise_id, s.kind AS kind,
            s.weight_kg AS weight_kg, s.reps AS reps, s.duration_sec AS duration_sec
        FROM set_log s
        JOIN session_exercise se ON s.session_exercise_id = se.id
        JOIN workout_session w ON se.session_id = w.id
        WHERE w.status = 'finished'
        ORDER BY w.started_at, se.position, s.position
        """,
    )
    abstract fun observeHistorySets(): Flow<List<HistorySetRow>>

    /** Sets of finished sessions in generated programs of one mesocycle, with the program week. */
    @Query(
        """
        SELECT w.week_index AS week_index, se.exercise_id AS exercise_id, s.kind AS kind
        FROM set_log s
        JOIN session_exercise se ON s.session_exercise_id = se.id
        JOIN workout_session w ON se.session_id = w.id
        JOIN program p ON w.program_id = p.id
        WHERE w.status = 'finished' AND w.week_index IS NOT NULL AND p.source = 'generated'
            AND p.mesocycle_index = :mesocycleIndex
        """,
    )
    abstract fun observeWeekSets(mesocycleIndex: Int): Flow<List<WeekSetRow>>

    @Query(
        """
        SELECT w.week_index AS week_index, COUNT(*) AS count FROM workout_session w
        JOIN program p ON w.program_id = p.id
        WHERE w.status = 'finished' AND w.week_index IS NOT NULL AND p.source = 'generated'
            AND p.mesocycle_index = :mesocycleIndex
        GROUP BY w.week_index
        """,
    )
    abstract fun observeWeekSessions(mesocycleIndex: Int): Flow<List<WeekCountRow>>

    @Query("SELECT * FROM workout_session WHERE status = 'active' ORDER BY started_at DESC LIMIT 1")
    abstract fun observeActive(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_session WHERE status = 'active' ORDER BY started_at DESC LIMIT 1")
    abstract suspend fun active(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_session WHERE id = :id")
    abstract fun observeSession(id: Long): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_session WHERE id = :id")
    abstract suspend fun session(id: Long): WorkoutSessionEntity?

    @Transaction
    @Query("SELECT * FROM session_exercise WHERE session_id = :sessionId ORDER BY position")
    abstract fun observeSlots(sessionId: Long): Flow<List<SessionExerciseWithSets>>

    @Transaction
    @Query("SELECT * FROM session_exercise WHERE session_id = :sessionId ORDER BY position")
    abstract suspend fun slots(sessionId: Long): List<SessionExerciseWithSets>

    @Query(
        "UPDATE session_exercise SET exercise_id = :exerciseId, rep_min = :repMin, rep_max = :repMax, " +
            "target_weight_kg = :targetWeightKg WHERE id = :slotId",
    )
    abstract suspend fun replaceSlotExercise(slotId: Long, exerciseId: String, repMin: Int, repMax: Int, targetWeightKg: Double?)

    @Insert
    abstract suspend fun insertSlot(slot: SessionExerciseEntity): Long

    @Query("UPDATE session_exercise SET position = :position WHERE id = :slotId")
    abstract suspend fun setSlotPosition(slotId: Long, position: Int)

    @Query("UPDATE session_exercise SET sets = :sets WHERE id = :slotId")
    abstract suspend fun setSlotSets(slotId: Long, sets: Int)

    @Insert
    abstract suspend fun insertBusyEvent(event: BusyEventEntity): Long

    @Query("SELECT * FROM busy_event WHERE session_id = :sessionId ORDER BY created_at")
    abstract suspend fun busyEvents(sessionId: Long): List<BusyEventEntity>

    @Query(
        "SELECT exercise_id, COUNT(*) AS count FROM busy_event WHERE action != 'wait' " +
            "GROUP BY exercise_id ORDER BY count DESC, exercise_id LIMIT :limit",
    )
    abstract fun observeBusiest(limit: Int): Flow<List<BusyCountRow>>

    @Query("UPDATE workout_session SET current_position = :position WHERE id = :id")
    abstract suspend fun setPosition(id: Long, position: Int)

    @Query("UPDATE workout_session SET rest_started_at = :startedAt, rest_ends_at = :endsAt WHERE id = :id")
    abstract suspend fun setRest(id: Long, startedAt: Long?, endsAt: Long?)

    @Query(
        "UPDATE workout_session SET status = 'finished', finished_at = :finishedAt, " +
            "rest_started_at = NULL, rest_ends_at = NULL WHERE id = :id",
    )
    abstract suspend fun markFinished(id: Long, finishedAt: Long)

    @Query("UPDATE workout_session SET feeling = :feeling, note = :note WHERE id = :id")
    abstract suspend fun setFeedback(id: Long, feeling: Int?, note: String?)

    @Query("UPDATE workout_session SET progress_json = :json WHERE id = :id")
    abstract suspend fun setProgress(id: Long, json: String?)

    @Query("SELECT exercise_id, COUNT(*) AS count FROM busy_event WHERE action != 'wait' GROUP BY exercise_id")
    abstract suspend fun busyCounts(): List<BusyCountRow>

    /** Most recent finished sessions of a program, newest first, for the check-in based deload advice. */
    @Query("SELECT * FROM workout_session WHERE status = 'finished' AND program_id = :programId ORDER BY started_at DESC LIMIT :limit")
    abstract suspend fun recentFinished(programId: Long, limit: Int): List<WorkoutSessionEntity>

    @Query("DELETE FROM workout_session WHERE id = :id")
    abstract suspend fun deleteSession(id: Long)

    @Query(
        """
        SELECT w.id AS id, w.title AS title, w.started_at AS started_at, w.finished_at AS finished_at,
            COALESCE(SUM(CASE WHEN s.kind != 'warmup' AND s.weight_kg > 0 AND s.reps > 0
                THEN s.weight_kg * s.reps ELSE 0 END), 0) AS volume_kg,
            COALESCE(SUM(CASE WHEN s.id IS NOT NULL AND s.kind != 'warmup' THEN 1 ELSE 0 END), 0) AS work_sets
        FROM workout_session w
        LEFT JOIN session_exercise se ON se.session_id = w.id
        LEFT JOIN set_log s ON s.session_exercise_id = se.id
        WHERE w.status = 'finished'
        GROUP BY w.id
        ORDER BY w.started_at DESC
        """,
    )
    abstract fun observeHistory(): Flow<List<HistoryRow>>

    @Query(
        """
        SELECT w.id AS id, w.title AS title, w.started_at AS started_at, w.finished_at AS finished_at,
            COALESCE(SUM(CASE WHEN s.kind != 'warmup' AND s.weight_kg > 0 AND s.reps > 0
                THEN s.weight_kg * s.reps ELSE 0 END), 0) AS volume_kg,
            COALESCE(SUM(CASE WHEN s.id IS NOT NULL AND s.kind != 'warmup' THEN 1 ELSE 0 END), 0) AS work_sets
        FROM workout_session w
        LEFT JOIN session_exercise se ON se.session_id = w.id
        LEFT JOIN set_log s ON s.session_exercise_id = se.id
        WHERE w.status = 'finished' AND w.program_day_id = :programDayId AND w.started_at < :before
        GROUP BY w.id
        ORDER BY w.started_at DESC
        LIMIT 1
        """,
    )
    abstract suspend fun previousForDay(programDayId: Long, before: Long): HistoryRow?

    /** Sets of the most recent finished session (other than [excludeSessionId]) that contained [exerciseId]. */
    @Query(
        """
        SELECT s.* FROM set_log s
        JOIN session_exercise se ON s.session_exercise_id = se.id
        WHERE se.exercise_id = :exerciseId AND se.session_id = (
            SELECT w.id FROM workout_session w
            JOIN session_exercise se2 ON se2.session_id = w.id
            WHERE se2.exercise_id = :exerciseId AND w.status = 'finished' AND w.id != :excludeSessionId
            ORDER BY w.started_at DESC LIMIT 1
        )
        ORDER BY se.position, s.position
        """,
    )
    abstract suspend fun lastResult(exerciseId: String, excludeSessionId: Long): List<SetLogEntity>

    @Query(
        """
        SELECT se.exercise_id AS exercise_id, w.id AS session_id, s.kind AS kind, s.weight_kg AS weight_kg,
            s.reps AS reps, s.duration_sec AS duration_sec
        FROM set_log s
        JOIN session_exercise se ON s.session_exercise_id = se.id
        JOIN workout_session w ON se.session_id = w.id
        WHERE se.exercise_id IN (:exerciseIds) AND w.status = 'finished' AND w.started_at < :before
        """,
    )
    abstract suspend fun historySets(exerciseIds: List<String>, before: Long): List<ExerciseSetRow>
}

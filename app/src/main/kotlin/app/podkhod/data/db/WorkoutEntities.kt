package app.podkhod.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * The whole active workout lives here, not in screen memory: every set and every rest timer change is
 * written immediately so the session survives process death and a dead battery.
 */
@Entity(
    tableName = "workout_session",
    indices = [Index("status"), Index("started_at")],
)
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "program_id") val programId: Long?,
    @ColumnInfo(name = "program_day_id") val programDayId: Long?,
    @ColumnInfo(name = "day_index") val dayIndex: Int?,
    val title: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    /** "active" or "finished". */
    val status: String,
    @ColumnInfo(name = "current_position") val currentPosition: Int,
    @ColumnInfo(name = "rest_started_at") val restStartedAt: Long?,
    @ColumnInfo(name = "rest_ends_at") val restEndsAt: Long?,
    /** 1 (hard) .. 3 (easy); null until rated. */
    val feeling: Int?,
    val note: String?,
    @ColumnInfo(name = "week_index") val weekIndex: Int? = null,
    @ColumnInfo(defaultValue = "0") val deload: Boolean = false,
    /** Check-in answers 0 = хорошо, 1 = средне, 2 = плохо; null when skipped. */
    @ColumnInfo(name = "checkin_sleep") val checkinSleep: Int? = null,
    @ColumnInfo(name = "checkin_legs") val checkinLegs: Int? = null,
    @ColumnInfo(name = "checkin_ready") val checkinReady: Int? = null,
    @ColumnInfo(name = "checkin_long") val checkinLong: Boolean? = null,
    /** Today's plan adjustments (deload, check-in, endurance), one per line. */
    @ColumnInfo(name = "plan_note") val planNote: String? = null,
    /** Progression messages written on finish, JSON array of {exercise, change, message}. */
    @ColumnInfo(name = "progress_json") val progressJson: String? = null,
) {
    companion object {
        const val ACTIVE = "active"
        const val FINISHED = "finished"
    }
}

@Entity(
    tableName = "session_exercise",
    foreignKeys = [
        ForeignKey(WorkoutSessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"]),
    ],
    indices = [Index("session_id"), Index("exercise_id")],
)
data class SessionExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val position: Int,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "planned_exercise_id") val plannedExerciseId: Long?,
    @ColumnInfo(name = "superset_group") val supersetGroup: String?,
    val sets: Int,
    @ColumnInfo(name = "rep_min") val repMin: Int,
    @ColumnInfo(name = "rep_max") val repMax: Int,
    @ColumnInfo(name = "rir_min") val rirMin: Int?,
    @ColumnInfo(name = "rir_max") val rirMax: Int?,
    @ColumnInfo(name = "rest_sec") val restSec: Int,
    @ColumnInfo(name = "target_weight_kg") val targetWeightKg: Double?,
    val note: String?,
)

@Entity(
    tableName = "set_log",
    foreignKeys = [ForeignKey(SessionExerciseEntity::class, ["id"], ["session_exercise_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_exercise_id")],
)
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_exercise_id") val sessionExerciseId: Long,
    val position: Int,
    /** SetKind code: warmup / work / failure / drop. */
    val kind: String,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    val reps: Int?,
    @ColumnInfo(name = "duration_sec") val durationSec: Int?,
    val rir: Int?,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
)

/**
 * Every "busy" tap, so the app can learn which stations are usually taken. Kept when a workout is
 * discarded: the gym was still busy.
 */
@Entity(
    tableName = "busy_event",
    foreignKeys = [ForeignKey(WorkoutSessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("session_id"), Index("exercise_id")],
)
data class BusyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    /** One of [LATER], [REPLACE_TODAY], [REPLACE_PROGRAM], [WAIT]. */
    val action: String,
    @ColumnInfo(name = "replacement_id") val replacementId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val LATER = "later"
        const val REPLACE_TODAY = "replace_today"
        const val REPLACE_PROGRAM = "replace_program"
        const val WAIT = "wait"
    }
}

data class BusyCountRow(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val count: Int,
)

data class SessionExerciseWithSets(
    @Embedded val slot: SessionExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "session_exercise_id")
    val sets: List<SetLogEntity>,
)

data class ProgramDayWithExercises(
    @Embedded val day: ProgramDayEntity,
    @Relation(parentColumn = "id", entityColumn = "program_day_id")
    val exercises: List<PlannedExerciseEntity>,
)

data class HistoryRow(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "volume_kg") val volumeKg: Double,
    @ColumnInfo(name = "work_sets") val workSets: Int,
)

data class ExerciseSetRow(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val kind: String,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    val reps: Int?,
    @ColumnInfo(name = "duration_sec") val durationSec: Int?,
)

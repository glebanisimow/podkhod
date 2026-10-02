package app.podkhod.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "exercise")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name_ru") val nameRu: String,
    @ColumnInfo(name = "name_en") val nameEn: String,
    val pattern: String,
    val kind: String,
    val measure: String,
    val load: String,
    @ColumnInfo(name = "rep_min") val repMin: Int,
    @ColumnInfo(name = "rep_max") val repMax: Int,
    @ColumnInfo(name = "rest_sec") val restSec: Int,
    @ColumnInfo(name = "step_kg") val stepKg: Double,
    @ColumnInfo(name = "occupancy_risk") val occupancyRisk: String,
    val difficulty: Int,
    val fatigue: Int,
    @ColumnInfo(name = "technique_json") val techniqueJson: String?,
    @ColumnInfo(name = "media_path") val mediaPath: String?,
    @ColumnInfo(name = "strava_category") val stravaCategory: String?,
    val reviewed: Boolean,
    @ColumnInfo(name = "catalog_version") val catalogVersion: Int,
)

@Entity(
    tableName = "exercise_equipment",
    primaryKeys = ["exercise_id", "equipment"],
    foreignKeys = [ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"], onDelete = ForeignKey.CASCADE)],
)
data class ExerciseEquipmentEntity(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val equipment: String,
)

@Entity(
    tableName = "exercise_muscle",
    primaryKeys = ["exercise_id", "muscle"],
    foreignKeys = [ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("muscle")],
)
data class ExerciseMuscleEntity(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val muscle: String,
    /** "primary" or "secondary". */
    val role: String,
)

@Entity(
    tableName = "exercise_tag",
    primaryKeys = ["exercise_id", "tag"],
    foreignKeys = [ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"], onDelete = ForeignKey.CASCADE)],
)
data class ExerciseTagEntity(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val tag: String,
)

@Entity(
    tableName = "exercise_substitute",
    primaryKeys = ["exercise_id", "substitute_id"],
    foreignKeys = [ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("substitute_id")],
)
data class ExerciseSubstituteEntity(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "substitute_id") val substituteId: String,
    val rank: Int,
)

@Entity(tableName = "program")
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "template" or "generated". */
    val source: String,
    @ColumnInfo(name = "template_id") val templateId: String?,
    /** "active" or "archived". */
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "next_day_index") val nextDayIndex: Int,
    /** Mesocycle length in program weeks (full rotations of the days). */
    @ColumnInfo(defaultValue = "6") val weeks: Int = 6,
    /** 0-based; the last week is the planned deload; equal to [weeks] means the mesocycle is over. */
    @ColumnInfo(name = "week_index", defaultValue = "0") val weekIndex: Int = 0,
    @ColumnInfo(name = "mesocycle_index", defaultValue = "0") val mesocycleIndex: Int = 0,
    val style: String? = null,
    /** Generator explanation: program notes and shortfalls, JSON. */
    @ColumnInfo(name = "explanation_json") val explanationJson: String? = null,
    /** Reason of an offered unplanned deload; null when nothing is offered. */
    @ColumnInfo(name = "deload_offer") val deloadOffer: String? = null,
    /** Sessions left in an accepted unplanned deload. */
    @ColumnInfo(name = "deload_sessions_left", defaultValue = "0") val deloadSessionsLeft: Int = 0,
)

@Entity(
    tableName = "program_day",
    foreignKeys = [ForeignKey(ProgramEntity::class, ["id"], ["program_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("program_id")],
)
data class ProgramDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "program_id") val programId: Long,
    @ColumnInfo(name = "seq_index") val seqIndex: Int,
    val title: String,
)

@Entity(
    tableName = "planned_exercise",
    foreignKeys = [
        ForeignKey(ProgramDayEntity::class, ["id"], ["program_day_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"]),
    ],
    indices = [Index("program_day_id"), Index("exercise_id")],
)
data class PlannedExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "program_day_id") val programDayId: Long,
    val position: Int,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "superset_group") val supersetGroup: String?,
    val sets: Int,
    @ColumnInfo(name = "rep_min") val repMin: Int,
    @ColumnInfo(name = "rep_max") val repMax: Int,
    @ColumnInfo(name = "rir_min") val rirMin: Int?,
    @ColumnInfo(name = "rir_max") val rirMax: Int?,
    @ColumnInfo(name = "rest_sec") val restSec: Int,
    @ColumnInfo(name = "target_weight_kg") val targetWeightKg: Double?,
    val note: String?,
    val locked: Boolean,
    /** Generator slot role (compound/accessory/isolation/core); null for template programs. */
    val role: String? = null,
    @ColumnInfo(defaultValue = "0") val marker: Boolean = false,
    /** "Почему так?" lines as a JSON array. */
    @ColumnInfo(name = "explanation_json") val explanationJson: String? = null,
)

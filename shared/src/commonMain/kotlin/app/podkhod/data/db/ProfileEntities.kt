package app.podkhod.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single user profile (id is always [SINGLE_ID]). List-valued fields are comma-separated codes:
 * they are short, never queried by element and keep the schema flat.
 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ID,
    val goal: String,
    /** Muscle group codes, at most two. */
    val priorities: String,
    @ColumnInfo(name = "days_per_week") val daysPerWeek: Int,
    @ColumnInfo(name = "session_minutes") val sessionMinutes: Int,
    /** ISO weekdays 1..7. */
    @ColumnInfo(name = "preferred_days") val preferredDays: String,
    @ColumnInfo(name = "long_effort_days") val longEffortDays: String,
    @ColumnInfo(name = "cardio_days") val cardioDays: String,
    val style: String,
    @ColumnInfo(name = "mesocycle_weeks") val mesocycleWeeks: Int,
    @ColumnInfo(name = "bar_kg") val barKg: Double,
    /** Equipment checklist codes. */
    val equipment: String,
    @ColumnInfo(name = "restriction_tags") val restrictionTags: String,
    @ColumnInfo(name = "restriction_note") val restrictionNote: String,
    @ColumnInfo(name = "body_mass_kg") val bodyMassKg: Double?,
    @ColumnInfo(name = "excluded_exercises") val excludedExercises: String,
    /** Only "kg" for now; the column keeps room for pounds without a migration. */
    val units: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val SINGLE_ID = 1
    }
}

@Entity(tableName = "plate")
data class PlateEntity(
    @PrimaryKey @ColumnInfo(name = "weight_kg") val weightKg: Double,
    val pairs: Int,
    /** ARGB; null uses the default palette for this weight. */
    val color: Long?,
)

@Entity(tableName = "kettlebell")
data class KettlebellEntity(
    @PrimaryKey @ColumnInfo(name = "weight_kg") val weightKg: Double,
    val count: Int,
)

@Entity(tableName = "dumbbell")
data class DumbbellEntity(
    @PrimaryKey @ColumnInfo(name = "weight_kg") val weightKg: Double,
    val pairs: Int,
)

/** Onboarding lifts; for pull-ups and dips [weightKg] is the added weight. */
@Entity(tableName = "baseline_lift")
data class BaselineLiftEntity(
    @PrimaryKey val movement: String,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    val reps: Int?,
    @ColumnInfo(name = "e1rm_override_kg") val e1rmOverrideKg: Double?,
    val skipped: Boolean,
)

/**
 * Double-progression state per exercise. Keyed by exercise, not program: a lift's working weight carries
 * over to the next mesocycle.
 */
@Entity(tableName = "progression_state")
data class ProgressionStateEntity(
    @PrimaryKey @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "working_weight_kg") val workingWeightKg: Double?,
    @ColumnInfo(name = "misses_in_row") val missesInRow: Int,
    /** JSON array of the last session e1RMs, oldest first. */
    @ColumnInfo(name = "e1rm_history_json") val e1rmHistoryJson: String,
    val note: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

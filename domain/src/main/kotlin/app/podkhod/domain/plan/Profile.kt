package app.podkhod.domain.plan

import app.podkhod.domain.model.Muscle
import app.podkhod.domain.weights.Inventory

/** Codes are persisted; never rename an existing constant. */
enum class Goal {
    STRENGTH, HYPERTROPHY, BALANCE;

    val code: String get() = name.lowercase()

    companion object {
        fun fromCode(code: String?): Goal = entries.firstOrNull { it.code == code } ?: BALANCE
    }
}

enum class Style {
    INTENSIVE, CLASSIC;

    val code: String get() = name.lowercase()

    companion object {
        fun fromCode(code: String?): Style = entries.firstOrNull { it.code == code } ?: INTENSIVE
    }
}

/** Groups the weekly volume targets are set for; forearms and lower back are trained only indirectly. */
enum class MuscleGroup(val muscles: Set<Muscle>) {
    CHEST(setOf(Muscle.CHEST)),
    BACK(setOf(Muscle.LATS, Muscle.UPPER_BACK)),
    QUADS(setOf(Muscle.QUADS)),
    HAMSTRINGS(setOf(Muscle.HAMSTRINGS)),
    GLUTES(setOf(Muscle.GLUTES)),
    DELTS(setOf(Muscle.DELTS_FRONT, Muscle.DELTS_SIDE, Muscle.DELTS_REAR)),
    BICEPS(setOf(Muscle.BICEPS)),
    TRICEPS(setOf(Muscle.TRICEPS)),
    CALVES(setOf(Muscle.CALVES)),
    ABS(setOf(Muscle.ABS));

    val code: String get() = name.lowercase()

    val isLegs: Boolean get() = this in LEGS

    companion object {
        val LEGS = setOf(QUADS, HAMSTRINGS, GLUTES, CALVES)
        fun fromCode(code: String): MuscleGroup? = entries.firstOrNull { it.code == code }
        fun of(muscle: Muscle): MuscleGroup? = entries.firstOrNull { muscle in it.muscles }
    }
}

/** Lifts asked in onboarding; reference exercises are mapped in the generator config. */
enum class BaselineLift(val bodyweight: Boolean = false) {
    SQUAT, BENCH, DEADLIFT, RDL, OHP, ROW, PULLUP(bodyweight = true), DIPS(bodyweight = true), KB_SWING, KB_SNATCH, KB_GOBLET;

    val code: String get() = name.lowercase()

    companion object {
        fun fromCode(code: String): BaselineLift? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A recent set the user can do: [weightKg] × [reps] (for pull-ups and dips the added weight), or a
 * directly entered 1RM in [e1rmOverrideKg]. [skipped] means "не делаю": the reference exercise is excluded.
 */
data class Baseline(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val e1rmOverrideKg: Double? = null,
    val skipped: Boolean = false,
)

data class Profile(
    val goal: Goal = Goal.BALANCE,
    /** At most two. */
    val priorities: List<MuscleGroup> = emptyList(),
    val daysPerWeek: Int = 3,
    val sessionMinutes: Int = 45,
    /** ISO weekdays 1..7 the user prefers to train on; informational for now (weeks vary). */
    val preferredDays: Set<Int> = emptySet(),
    /** ISO weekdays with a long ride or run: legs are eased the day before. */
    val longEffortDays: Set<Int> = emptySet(),
    /** ISO weekdays with regular cardio that is not long. */
    val cardioDays: Set<Int> = emptySet(),
    val style: Style = Style.INTENSIVE,
    val mesocycleWeeks: Int = 6,
    val baselines: Map<BaselineLift, Baseline> = emptyMap(),
    val inventory: Inventory = Inventory(),
    val restrictionTags: Set<String> = emptySet(),
    val restrictionNote: String = "",
    val bodyMassKg: Double? = null,
    /** Exercises the user never wants; locked exercises win over this. */
    val excludedExercises: Set<String> = emptySet(),
)

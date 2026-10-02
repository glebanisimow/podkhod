package app.podkhod.domain.model

/** Codes are persisted as lowercase strings; never rename an existing constant. */
enum class Muscle {
    CHEST, LATS, UPPER_BACK, DELTS_FRONT, DELTS_SIDE, DELTS_REAR, BICEPS, TRICEPS, FOREARMS,
    QUADS, HAMSTRINGS, GLUTES, CALVES, ABS, LOWER_BACK;

    val code: String get() = name.lowercase()

    companion object {
        fun fromCode(code: String): Muscle = valueOf(code.uppercase())
    }
}

enum class MovementPattern {
    SQUAT, HINGE, LUNGE, H_PUSH, V_PUSH, H_PULL, V_PULL, ISOLATION, CORE, CARRY;

    val code: String get() = name.lowercase()

    companion object {
        fun fromCode(code: String): MovementPattern = valueOf(code.uppercase())
    }
}

enum class ExerciseKind {
    COMPOUND, ACCESSORY, ISOLATION;

    val code: String get() = name.lowercase()

    companion object {
        fun fromCode(code: String): ExerciseKind = valueOf(code.uppercase())
    }
}

enum class Measure {
    REPS, SECONDS;

    val code: String get() = name.lowercase()

    companion object {
        fun fromCode(code: String): Measure = valueOf(code.uppercase())
    }
}

/** EXTERNAL: weight is the load; BODYWEIGHT_PLUS: weight is extra load on top of body mass; NONE: no weight input. */
enum class LoadType {
    EXTERNAL, BODYWEIGHT_PLUS, NONE;

    val code: String get() = name.lowercase()

    companion object {
        fun fromCode(code: String): LoadType = valueOf(code.uppercase())
    }
}

enum class SetKind {
    WARMUP, WORK, FAILURE, DROP;

    val code: String get() = name.lowercase()

    /** Warm-up sets never count towards volume, records or progression. */
    val countsAsWork: Boolean get() = this != WARMUP

    companion object {
        fun fromCode(code: String): SetKind = valueOf(code.uppercase())
    }
}

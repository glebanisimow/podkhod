package app.podkhod.domain.plan

import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.busy.Occupancy
import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import java.io.File

/** The bundled config: domain tests check behaviour with the numbers the app ships. */
val bundledConfig: GeneratorConfig by lazy {
    val candidates = listOf(File("../shared/src/commonMain/composeResources/files/generator_config.json"), File("shared/src/commonMain/composeResources/files/generator_config.json"))
    GeneratorConfig.parse(candidates.first { it.exists() }.readText(Charsets.UTF_8))
}

fun exercise(
    id: String,
    pattern: MovementPattern = MovementPattern.H_PUSH,
    kind: ExerciseKind = ExerciseKind.COMPOUND,
    equipment: Set<String> = setOf("barbell", "bench"),
    primary: Set<Muscle> = setOf(Muscle.CHEST),
    load: LoadType = LoadType.EXTERNAL,
    measure: Measure = Measure.REPS,
    implements: Int = 1,
    step: Double = 2.5,
) = ExerciseInfo(
    id = id, pattern = pattern, load = load, measure = measure, repMin = 4, repMax = 12, stepKg = step,
    occupancy = Occupancy.MEDIUM, equipment = equipment, primary = primary, kind = kind, implements = implements, name = id,
)

fun slot(
    id: String,
    sets: Int = 3,
    repMin: Int = 6,
    repMax: Int = 8,
    rirMin: Int = 1,
    rirMax: Int = 2,
    weight: Double? = 80.0,
    role: SlotRole = SlotRole.COMPOUND,
) = PlannedSlot(
    exerciseId = id, role = role, sets = sets, repMin = repMin, repMax = repMax, rirMin = rirMin, rirMax = rirMax,
    restSec = 105, targetWeightKg = weight,
)

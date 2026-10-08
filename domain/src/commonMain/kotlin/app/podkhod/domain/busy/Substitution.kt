package app.podkhod.domain.busy

import app.podkhod.domain.OneRepMax
import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import kotlin.math.abs
import kotlin.math.floor

enum class Occupancy {
    LOW, MEDIUM, HIGH;

    companion object {
        fun fromCode(code: String): Occupancy = valueOf(code.uppercase())
    }
}

/** Everything the busy mode and the program generator need to know about a catalog exercise. */
data class ExerciseInfo(
    val id: String,
    val pattern: MovementPattern,
    val load: LoadType,
    val measure: Measure,
    val repMin: Int,
    val repMax: Int,
    val stepKg: Double,
    val occupancy: Occupancy,
    val equipment: Set<String>,
    val primary: Set<Muscle>,
    val secondary: Set<Muscle> = emptySet(),
    /** Catalog substitution chain, best first. */
    val substitutes: List<String> = emptyList(),
    val kind: ExerciseKind = ExerciseKind.ACCESSORY,
    val tags: Set<String> = emptySet(),
    val difficulty: Int = 1,
    val fatigue: Int = 1,
    val restSec: Int = 90,
    /** Kettlebells or dumbbells used at once (2 for double-kettlebell lifts); weights are per implement. */
    val implements: Int = 1,
    /** Position in the catalog file: the canonical variant of a movement comes first and wins ties. */
    val order: Int = 0,
    /** Russian display name, used in generator explanations. */
    val name: String = id,
)

/**
 * Relative strength of an exercise inside a family of comparable lifts: the logged weight that matches
 * the family's reference lift at ratio 1.0. Weights of single/double kettlebell and dumbbell lifts are
 * per implement, as they are logged.
 */
data class StrengthRatio(val family: String, val ratio: Double)

data class SubstitutionConfig(
    /** Shared gym stations: when the original is busy, alternatives that need the same station are pushed back. */
    val stations: Set<String>,
    /** Equipment the user does not have; stage 2 replaces this with the profile inventory. */
    val unavailableEquipment: Set<String>,
    /** Work sets per primary muscle in one session above which a substitute is considered overloading. */
    val sessionMuscleCap: Int,
    /** Multiplier applied to converted weights so that a guess errs on the light side. */
    val safetyFactor: Double,
    val ratios: Map<String, StrengthRatio>,
    /** Light fillers for "what to do while waiting", in order of preference. */
    val waitExtras: List<String>,
)

/** A recent working set of the original exercise; [reps] is null for timed exercises. */
data class ReferenceSet(val weightKg: Double, val reps: Int?)

data class SessionSlotInfo(val exerciseId: String, val sets: Int)

data class SubstituteRequest(
    val original: ExerciseInfo,
    val repMin: Int,
    val repMax: Int,
    /** Work sets of the original still to do; these move to the substitute. */
    val remainingSets: Int,
    val reference: ReferenceSet?,
    /** All slots of the session except the one being replaced. */
    val otherSlots: List<SessionSlotInfo>,
    /** Own last working weight of candidates that were done before. */
    val ownLastWeight: Map<String, Double> = emptyMap(),
)

enum class EstimateSource { OWN_HISTORY, RATIO, BODYWEIGHT, NO_LOAD, UNKNOWN }

/** [weightKg] is null when there is no basis for a number: the UI asks to find it on a warm-up set. */
data class LoadEstimate(val weightKg: Double?, val repMin: Int, val repMax: Int, val source: EstimateSource)

data class SubstituteOption(
    val exerciseId: String,
    val estimate: LoadEstimate,
    val sameMovement: Boolean,
    val sharesBusyStation: Boolean,
    val overloadsMuscle: Boolean,
)

object Substitution {

    /**
     * Up to [limit] substitutes for a busy exercise, ordered by: does not need the busy station, same
     * movement and muscles, keeps today's per-muscle volume under the cap, lowest occupancy risk,
     * closest load profile, then the catalog chain order.
     */
    fun options(
        request: SubstituteRequest,
        catalog: Map<String, ExerciseInfo>,
        config: SubstitutionConfig,
        limit: Int = 3,
    ): List<SubstituteOption> {
        val original = request.original
        val busy = busyEquipment(original, config)
        val inSession = request.otherSlots.map { it.exerciseId }.toSet() + original.id
        val muscleSets = primarySetsByMuscle(request.otherSlots, catalog)

        val pool = LinkedHashSet<String>()
        original.substitutes.forEach { pool += it }
        original.substitutes.forEach { id -> catalog[id]?.substitutes?.forEach { pool += it } }

        return pool.asSequence()
            .filter { it !in inSession }
            .mapNotNull { catalog[it] }
            .filter { it.equipment.none { e -> e in config.unavailableEquipment } }
            .mapIndexed { chainIndex, candidate ->
                val estimate = estimate(original, candidate, request, config)
                val match = matchLevel(original, candidate)
                val overloads = (candidate.primary - original.primary).any { m ->
                    (muscleSets[m] ?: 0) + request.remainingSets > config.sessionMuscleCap
                }
                Ranked(
                    option = SubstituteOption(
                        exerciseId = candidate.id,
                        estimate = estimate,
                        sameMovement = sameMovement(original, candidate),
                        sharesBusyStation = candidate.equipment.any { it in busy },
                        overloadsMuscle = overloads,
                    ),
                    match = match,
                    occupancy = candidate.occupancy.ordinal,
                    loadDistance = loadDistance(original, candidate, estimate, config),
                    chainIndex = chainIndex,
                )
            }
            .sortedWith(
                compareBy<Ranked>(
                    { it.option.sharesBusyStation },
                    { it.match },
                    { it.option.overloadsMuscle },
                    { it.occupancy },
                    { it.loadDistance },
                    { it.chainIndex },
                ),
            )
            .take(limit)
            .map { it.option }
            .toList()
    }

    /** Converts the original's recent set to a starting load for [candidate]; never guesses without a basis. */
    fun estimate(
        original: ExerciseInfo,
        candidate: ExerciseInfo,
        request: SubstituteRequest,
        config: SubstitutionConfig,
    ): LoadEstimate {
        val keepRange = original.measure == candidate.measure
        val repMin = if (keepRange) request.repMin else candidate.repMin
        val repMax = if (keepRange) request.repMax else candidate.repMax
        fun result(weight: Double?, source: EstimateSource) = LoadEstimate(weight, repMin, repMax, source)

        if (candidate.load == LoadType.NONE) return result(null, EstimateSource.NO_LOAD)
        request.ownLastWeight[candidate.id]?.let { return result(it, EstimateSource.OWN_HISTORY) }
        if (candidate.load == LoadType.BODYWEIGHT_PLUS) return result(0.0, EstimateSource.BODYWEIGHT)

        val reference = request.reference ?: return result(null, EstimateSource.UNKNOWN)
        val from = config.ratios[original.id]
        val to = config.ratios[candidate.id]
        if (original.load != LoadType.EXTERNAL || from == null || to == null || from.family != to.family || reference.weightKg <= 0.0) {
            return result(null, EstimateSource.UNKNOWN)
        }
        val sourceStrength = if (original.measure == Measure.REPS && reference.reps != null && reference.reps > 0) {
            OneRepMax.estimate(reference.weightKg, reference.reps).average
        } else {
            reference.weightKg
        }
        val targetStrength = sourceStrength / from.ratio * to.ratio
        val raw = if (candidate.measure == Measure.REPS) targetStrength / (1.0 + repMax / 30.0) else targetStrength
        val weight = floorToStep(raw * config.safetyFactor, candidate.stepKg)
        return if (weight <= 0.0) result(null, EstimateSource.UNKNOWN) else result(weight, EstimateSource.RATIO)
    }

    /** The station that is taken; an exercise without a station (kettlebell, bar) blocks its own equipment. */
    fun busyEquipment(original: ExerciseInfo, config: SubstitutionConfig): Set<String> {
        val stations = original.equipment.filter { it in config.stations }.toSet()
        return stations.ifEmpty { original.equipment }
    }

    fun floorToStep(weightKg: Double, stepKg: Double): Double {
        if (stepKg <= 0.0) return weightKg
        val steps = floor(weightKg / stepKg + 1e-9)
        return steps * stepKg
    }

    private fun sameMovement(original: ExerciseInfo, candidate: ExerciseInfo): Boolean =
        original.pattern == candidate.pattern && original.pattern != MovementPattern.ISOLATION &&
            (original.primary intersect candidate.primary).isNotEmpty()

    /** 0 is the best match. A different kind of load (bodyweight instead of a barbell) is one level worse. */
    private fun matchLevel(original: ExerciseInfo, candidate: ExerciseInfo): Int {
        val samePattern = original.pattern == candidate.pattern && original.pattern != MovementPattern.ISOLATION
        val overlap = (original.primary intersect candidate.primary).isNotEmpty()
        val base = when {
            samePattern && overlap -> 0
            overlap -> 1
            samePattern -> 2
            else -> 3
        }
        return base + if (original.load == candidate.load) 0 else 1
    }

    private fun loadDistance(original: ExerciseInfo, candidate: ExerciseInfo, estimate: LoadEstimate, config: SubstitutionConfig): Double {
        if (estimate.source == EstimateSource.OWN_HISTORY) return 0.0
        val from = config.ratios[original.id]
        val to = config.ratios[candidate.id]
        if (from != null && to != null && from.family == to.family) return abs(to.ratio / from.ratio - 1.0)
        return if (original.load == candidate.load) UNKNOWN_SAME_LOAD else UNKNOWN_OTHER_LOAD
    }

    private fun primarySetsByMuscle(slots: List<SessionSlotInfo>, catalog: Map<String, ExerciseInfo>): Map<Muscle, Int> {
        val result = mutableMapOf<Muscle, Int>()
        slots.forEach { s -> catalog[s.exerciseId]?.primary?.forEach { m -> result[m] = (result[m] ?: 0) + s.sets } }
        return result
    }

    private data class Ranked(
        val option: SubstituteOption,
        val match: Int,
        val occupancy: Int,
        val loadDistance: Double,
        val chainIndex: Int,
    )

    private const val UNKNOWN_SAME_LOAD = 10.0
    private const val UNKNOWN_OTHER_LOAD = 20.0
}

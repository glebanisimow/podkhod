package app.podkhod.domain.plan

import app.podkhod.domain.model.ExerciseKind
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import app.podkhod.domain.weights.WarmupConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every tunable number of the generator. Bundled as assets/generator_config.json; the user can export it,
 * edit it and import it back. All fields are required in the file so a typo is an import error rather than
 * a silent default.
 */
@Serializable
data class GeneratorConfig(
    val configVersion: Int,
    /** Weekly work sets per group: min..max. */
    val volume: Map<String, IntRangeDto>,
    /** Credit of a set for a muscle that is only secondary in the exercise. */
    val secondaryWeight: Double,
    /** Per-group override: abs only stabilise in squats and presses, so only direct work counts. */
    val secondaryWeightByGroup: Map<String, Double>,
    /** Extra weekly sets for a priority group, by training days per week. */
    val priorityBonus: Map<String, Int>,
    /** Intensive style with at most this many days targets the lower-middle part of the ranges. */
    val intensiveLowDaysMax: Int,
    /** Exercises per session by session length, per style. */
    val exercisesPerSession: Map<String, Map<String, Int>>,
    val mesocycle: MesocycleConfig,
    val splits: Map<String, List<DayTemplate>>,
    val prescription: Map<String, Map<String, RolePrescription>>,
    /** Isolation, machines, kettlebells and bodyweight work closer to failure than barbell lifts. */
    val rirNonBarbell: IntRangeDto,
    val ballistic: BallisticConfig,
    val goals: Map<String, GoalAdjustments>,
    val sets: SetLimits,
    val timing: TimingConfig,
    /** Lower class wins: barbell → kettlebells/dumbbells → bodyweight → machines. */
    val equipmentClass: Map<String, Int>,
    val defaultEquipmentClass: Int,
    /** Shared stations: two exercises on the same one are never put into a superset. */
    val stations: List<String>,
    val progression: ProgressionConfig,
    val deload: DeloadConfig,
    val checkin: CheckinConfig,
    val endurance: EnduranceConfig,
    /** Onboarding lift -> catalog exercise it was measured on. */
    val baselineExercises: Map<String, String>,
    /** Restriction tag -> catalog tags it excludes. */
    val restrictionTags: Map<String, List<String>>,
    /** Busy-mode events after which an exercise is ranked below its equals. */
    val busyThreshold: Int,
    val warmup: WarmupConfig,
) {
    fun rangeFor(group: MuscleGroup): IntRange = volume[group.code]?.toRange() ?: 0..0

    fun perSession(style: Style, minutes: Int): Int {
        val table = exercisesPerSession[style.code] ?: exercisesPerSession.values.first()
        val key = table.keys.mapNotNull { it.toIntOrNull() }.filter { it <= minutes }.maxOrNull()
            ?: table.keys.mapNotNull { it.toIntOrNull() }.min()
        return table.getValue(key.toString())
    }

    fun prescriptionFor(style: Style, role: SlotRole): RolePrescription =
        prescription.getValue(style.code).getValue(role.code)

    fun classOf(equipment: Set<String>): Int =
        equipment.mapNotNull { equipmentClass[it] }.minOrNull() ?: defaultEquipmentClass

    /** Human-readable problems; empty means the config is usable. */
    fun validate(): List<String> = buildList {
        MuscleGroup.entries.forEach { g ->
            val r = volume[g.code]
            if (r == null) add("volume: нет группы ${g.code}") else if (r.min < 0 || r.max < r.min) add("volume.${g.code}: неверный диапазон")
        }
        listOf("2", "3", "4").forEach { d ->
            val days = splits[d]
            if (days == null || days.size != d.toInt()) add("splits.$d: нужно ровно $d дня")
            days?.forEachIndexed { i, day ->
                if (day.slots.isEmpty()) add("splits.$d[$i]: пустой день")
                day.slots.forEach { s ->
                    if (SlotRole.fromCode(s.role) == null) add("splits.$d[$i]: роль ${s.role}")
                    s.patterns.forEach { p -> if (runCatching { MovementPattern.fromCode(p) }.isFailure) add("splits.$d[$i]: паттерн $p") }
                    s.muscles.forEach { m -> if (runCatching { Muscle.fromCode(m) }.isFailure) add("splits.$d[$i]: мышца $m") }
                }
            }
        }
        Style.entries.forEach { st ->
            SlotRole.entries.forEach { r -> if (prescription[st.code]?.get(r.code) == null) add("prescription.${st.code}.${r.code}: нет") }
            if (exercisesPerSession[st.code].isNullOrEmpty()) add("exercisesPerSession.${st.code}: нет")
        }
        if (mesocycle.minWeeks < 2 || mesocycle.maxWeeks < mesocycle.minWeeks) add("mesocycle: неверные границы")
        if (sets.min < 1 || sets.max < sets.min) add("sets: неверные границы")
        if (deload.volumeFactor !in 0.1..1.0 || deload.weightFactor !in 0.5..1.0) add("deload: множители вне диапазона")
        if (progression.failureReduction !in 0.0..0.3) add("progression.failureReduction: 0..0,3")
        BaselineLift.entries.forEach { b -> if (baselineExercises[b.code] == null) add("baselineExercises: нет ${b.code}") }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = false; prettyPrint = true; encodeDefaults = true }
        fun parse(text: String): GeneratorConfig = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class IntRangeDto(val min: Int, val max: Int) {
    fun toRange(): IntRange = min..max
}

@Serializable
data class MesocycleConfig(val weeks: Int, val minWeeks: Int, val maxWeeks: Int, val markers: Int)

@Serializable
data class DayTemplate(val title: String, val slots: List<SlotTemplate>)

/** A slot of a day; [patterns] and [muscles] narrow candidates, an empty list means "any". */
@Serializable
data class SlotTemplate(
    val role: String,
    val patterns: List<String> = emptyList(),
    val muscles: List<String> = emptyList(),
)

enum class SlotRole(val rank: Int) {
    COMPOUND(0), ACCESSORY(1), ISOLATION(2), CORE(3);

    val code: String get() = name.lowercase()

    /** Catalog kinds a slot takes at strict and relaxed matching. */
    val kinds: Set<ExerciseKind>
        get() = when (this) {
            COMPOUND -> setOf(ExerciseKind.COMPOUND)
            ACCESSORY -> setOf(ExerciseKind.ACCESSORY, ExerciseKind.COMPOUND)
            ISOLATION -> setOf(ExerciseKind.ISOLATION)
            CORE -> ExerciseKind.entries.toSet()
        }

    /** A heavy compound never fills an isolation slot: it would get isolation rest and RIR. */
    val relaxedKinds: Set<ExerciseKind>
        get() = when (this) {
            COMPOUND -> setOf(ExerciseKind.COMPOUND, ExerciseKind.ACCESSORY)
            ISOLATION -> setOf(ExerciseKind.ISOLATION, ExerciseKind.ACCESSORY)
            ACCESSORY, CORE -> ExerciseKind.entries.toSet()
        }

    companion object {
        fun fromCode(code: String): SlotRole? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
data class RolePrescription(val sets: Int, val reps: IntRangeDto, val rir: IntRangeDto, val restSec: Int)

@Serializable
data class BallisticConfig(val rir: IntRangeDto, val note: String)

/** Changes on top of the style prescription; null keeps the style value. */
@Serializable
data class GoalAdjustments(
    val compoundReps: IntRangeDto? = null,
    val compoundSets: Int? = null,
    val accessorySets: Int? = null,
    val isolationSets: Int? = null,
)

@Serializable
data class SetLimits(val min: Int, val max: Int, val compoundMax: Int)

@Serializable
data class TimingConfig(
    val workSec: Int,
    val transitionSec: Int,
    val barbellWarmupMin: Double,
    val lightWarmupSetMin: Double,
    /** A day may run this much over the chosen length before sets are cut. */
    val tolerance: Double,
)

@Serializable
data class ProgressionConfig(
    /** Weight jump by movement pattern code; "default" for the rest. */
    val stepKg: Map<String, Double>,
    val failureReduction: Double,
    val missesForReduction: Int,
    /** Starting weights are this share of what the 1RM formula gives, so week 1 is not a test. */
    val startFactor: Double,
    val e1rmHistory: Int,
)

@Serializable
data class DeloadConfig(
    val volumeFactor: Double,
    val weightFactor: Double,
    /** Unplanned deload: e1RM of a compound falls this many sessions in a row. */
    val e1rmDrops: Int,
    /** Unplanned deload: this many "very hard" check-ins in a row. */
    val hardCheckins: Int,
)

@Serializable
data class CheckinConfig(
    /** Bad answers that make a day "very hard": RIR goes up and a set comes off. */
    val hardAt: Int,
    val rirBump: Int,
    val setCut: Int,
)

@Serializable
data class EnduranceConfig(val legSetCut: Int, val legRirBump: Int, val heavyLegWeightFactor: Double)

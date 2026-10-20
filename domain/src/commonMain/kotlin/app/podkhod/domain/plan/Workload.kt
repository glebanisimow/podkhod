package app.podkhod.domain.plan

import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.model.Measure
import app.podkhod.domain.weights.LoadScheme
import app.podkhod.domain.weights.Loads
import app.podkhod.domain.workout.SupersetRestMode
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One calculation for generation, history adaptation and the explanation shown to the lifter. */
class Workload(
    private val config: GeneratorConfig,
    private val profile: Profile,
    private val catalog: Map<String, ExerciseInfo>,
    private val accessoryWarmupSets: Int = 1,
    private val restMode: SupersetRestMode = SupersetRestMode.AFTER_ROUND,
) {
    val budgetMinutes: Double = profile.sessionMinutes * (1 + config.timing.tolerance)

    fun volume(sets: Map<String, Int>): Map<MuscleGroup, Double> = MuscleGroup.entries.associateWith { group ->
        sets.entries.sumOf { (id, count) -> catalog[id]?.let { count * config.credit(it, group) } ?: 0.0 }
    }

    fun volume(slots: List<PlannedSlot>): Map<MuscleGroup, Double> =
        volume(slots.groupBy { it.exerciseId }.mapValues { (_, entries) -> entries.sumOf { it.sets } })

    fun lines(volume: Map<MuscleGroup, Double>): List<VolumeLine> = MuscleGroup.entries.map { group ->
        val range = config.rangeFor(group)
        val bonus = if (group in profile.priorities) config.priorityBonus[profile.daysPerWeek.toString()] ?: 0 else 0
        val high = if (profile.style == Style.INTENSIVE && profile.daysPerWeek <= config.intensiveLowDaysMax) {
            ceil((range.first + range.last) / 2.0).toInt()
        } else range.last
        VolumeLine(group, volume.getValue(group), range.first, range.last + bonus, range.first + bonus, high + bonus, group in profile.priorities)
    }

    fun shortfalls(lines: List<VolumeLine>): List<String> = lines.mapNotNull { line ->
        when {
            line.sets + 1e-9 < line.targetLow ->
                "${Fmt.group(line.group)}: ${Fmt.num(line.sets)} из ${line.targetLow} подходов за круг — ограничения времени, восстановления или выбора упражнений."
            line.sets - 1e-9 > line.max ->
                "${Fmt.group(line.group)}: ${Fmt.num(line.sets)} подходов за круг — выше ${line.max}; учтена и косвенная нагрузка."
            else -> null
        }
    }

    fun minutes(slots: List<PlannedSlot>): Double {
        val timing = config.timing
        fun work(slot: PlannedSlot) = if (catalog[slot.exerciseId]?.measure == Measure.SECONDS) slot.repMax else timing.workSec
        var seconds = 0.0
        val seen = mutableSetOf<Int>()
        slots.forEachIndexed { index, slot ->
            if (!seen.add(index)) return@forEachIndexed
            val partnerIndex = slot.supersetGroup?.let { group ->
                slots.indices.singleOrNull { it != index && slots[it].supersetGroup == group }
            }
            if (partnerIndex != null && partnerIndex !in seen && restMode == SupersetRestMode.AFTER_ROUND) {
                seen += partnerIndex
                val partner = slots[partnerIndex]
                val rounds = minOf(slot.sets, partner.sets)
                seconds += rounds * (work(slot) + work(partner) + maxOf(slot.restSec, partner.restSec))
                val longer = if (slot.sets >= partner.sets) slot else partner
                seconds += (longer.sets - rounds) * (work(longer) + longer.restSec)
            } else {
                seconds += slot.sets * (work(slot) + slot.restSec)
            }
            // Count a conservative final rest as well; this is a planning estimate, not a stopwatch.
        }
        slots.forEach { slot ->
            seconds += timing.transitionSec
            val info = catalog[slot.exerciseId] ?: return@forEach
            seconds += 60.0 * when (val scheme = Loads.scheme(info, profile.inventory)) {
                is LoadScheme.NoLoad, is LoadScheme.Added -> 0.0
                else -> if (scheme is LoadScheme.Barbell && slot.role == SlotRole.COMPOUND) {
                    timing.barbellWarmupMin
                } else timing.lightWarmupSetMin * accessoryWarmupSets
            }
        }
        return seconds / 60.0
    }

    fun day(title: String, slots: List<PlannedSlot>) = PlannedDay(title, slots, minutes(slots).roundToInt())

    /** A scheduling heuristic, NOT a physiological fatigue measurement. */
    fun effort(slots: List<PlannedSlot>): Double =
        slots.sumOf { it.sets * (1.0 + 0.25 * ((catalog[it.exerciseId]?.fatigue ?: 1) - 1).coerceAtLeast(0)) }
}

package app.podkhod.domain.plan

import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.Loads
import kotlin.math.roundToInt

/** Pre-workout check-in; each answer is 0 = хорошо, 1 = средне, 2 = плохо. */
data class Checkin(val sleep: Int = 0, val legs: Int = 0, val readiness: Int = 0, val longEffortTomorrow: Boolean = false) {
    val badAnswers: Int get() = listOf(sleep, legs, readiness).count { it >= 2 }
    fun isHard(config: GeneratorConfig): Boolean = badAnswers >= config.checkin.hardAt
}

data class SessionContext(
    /** 0-based week of the mesocycle (full rotations of the program). */
    val weekIndex: Int,
    val weeks: Int,
    /** The user accepted an unplanned deload for the current week. */
    val unplannedDeload: Boolean = false,
    val checkin: Checkin? = null,
    /** From the profile: tomorrow is one of the long-effort weekdays. */
    val longEffortTomorrow: Boolean = false,
) {
    val plannedDeload: Boolean get() = weekIndex == weeks - 1
    val deload: Boolean get() = plannedDeload || unplannedDeload
}

data class SessionPlan(val slots: List<PlannedSlot>, val deload: Boolean, val notes: List<String>)

/** Turns the program day into today's plan: deload week, check-in and endurance adjustments. */
class SessionPlanner(private val config: GeneratorConfig) {

    fun plan(day: List<PlannedSlot>, infos: Map<String, ExerciseInfo>, inventory: Inventory, ctx: SessionContext): SessionPlan {
        val notes = mutableListOf<String>()
        val c = ctx.checkin
        val hard = c?.isHard(config) == true
        val oneBad = c != null && c.badAnswers == 1
        val easeLegs = (c?.legs ?: 0) >= 2 || c?.longEffortTomorrow == true || ctx.longEffortTomorrow

        if (ctx.plannedDeload) {
            notes += "Разгрузочная неделя мезоцикла: подходы ×${Fmt.num(config.deload.volumeFactor)}, вес ×${Fmt.num(config.deload.weightFactor)}, прогрессии нет."
        } else if (ctx.unplannedDeload) {
            notes += "Внеплановая разгрузка (ты согласился): подходы ×${Fmt.num(config.deload.volumeFactor)}, вес ×${Fmt.num(config.deload.weightFactor)}."
        }
        if (hard) notes += "Чек-ин: очень тяжело — запас +${config.checkin.rirBump}, по ${config.checkin.setCut} подходу меньше."
        else if (oneBad) notes += "Чек-ин: один пункт «плохо» — запас +${config.checkin.rirBump}."
        if (easeLegs) {
            val why = if ((c?.legs ?: 0) >= 2) "ноги уставшие" else "завтра длинная нагрузка"
            notes += "Ноги облегчены ($why): −${config.endurance.legSetCut} подход и запас +${config.endurance.legRirBump}, " +
                "тяжёлые базовые ×${Fmt.num(config.endurance.heavyLegWeightFactor)}."
        }

        val slots = day.map { s ->
            val info = infos[s.exerciseId]
            val scheme = info?.let { Loads.scheme(it, inventory) }
            val reasons = mutableListOf<String>()
            var sets = s.sets
            var rirMin = s.rirMin
            var rirMax = s.rirMax
            var weight = s.targetWeightKg
            fun scale(factor: Double) {
                val w = weight ?: return
                if (w <= 0.0 || scheme == null) return
                weight = Loads.roundDown(scheme, w * factor)
            }
            if (ctx.deload) {
                sets = (sets * config.deload.volumeFactor).roundToInt().coerceAtLeast(1)
                scale(config.deload.weightFactor)
                reasons += "Разгрузка: ${s.sets} → $sets подх., вес ${Fmt.kg(s.targetWeightKg)} → ${Fmt.kg(weight)}."
            }
            if (hard || oneBad) {
                rirMin += config.checkin.rirBump
                rirMax += config.checkin.rirBump
            }
            if (hard) sets = (sets - config.checkin.setCut).coerceAtLeast(1)
            val legs = info != null && info.primary.mapNotNull(MuscleGroup::of).any { it.isLegs }
            if (easeLegs && legs) {
                sets = (sets - config.endurance.legSetCut).coerceAtLeast(1)
                rirMin += config.endurance.legRirBump
                rirMax += config.endurance.legRirBump
                if (s.role == SlotRole.COMPOUND) scale(config.endurance.heavyLegWeightFactor)
                reasons += "Ноги облегчены на сегодня."
            }
            s.copy(sets = sets, rirMin = rirMin, rirMax = rirMax, targetWeightKg = weight, reasons = s.reasons + reasons)
        }
        return SessionPlan(slots, ctx.deload, notes)
    }
}

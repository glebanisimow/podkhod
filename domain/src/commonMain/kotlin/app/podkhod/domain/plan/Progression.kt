package app.podkhod.domain.plan

import app.podkhod.domain.OneRepMax
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.weights.LoadScheme
import app.podkhod.domain.weights.Loads

data class ProgressionState(
    val workingWeightKg: Double? = null,
    val missesInRow: Int = 0,
    /** Best e1RM per session, oldest first. */
    val e1rmHistory: List<Double> = emptyList(),
    /** What to do next time when the weight cannot go up (no heavier bell, bodyweight). */
    val note: String? = null,
)

/** A logged work set; [reps] is null for timed sets, [rir] is null when the user did not rate it. */
data class LoggedSet(val weightKg: Double, val reps: Int?, val durationSec: Int? = null, val rir: Int? = null)

enum class Change { UP, HOLD, DOWN, NONE }

data class ProgressionResult(val state: ProgressionState, val change: Change, val message: String)

/**
 * Double progression: when every planned work set reaches the top of the rep range without dipping under
 * the target reserve, the weight goes to the next buildable step. Two sessions in a row with a set below
 * the bottom of the range take the weight down by the configured share.
 */
class Progression(private val config: GeneratorConfig) {

    fun stepFor(info: ExerciseInfo): Double = config.progression.stepKg[info.pattern.code] ?: config.progression.stepKg.getValue("default")

    fun update(
        info: ExerciseInfo,
        scheme: LoadScheme,
        slot: PlannedSlot,
        sets: List<LoggedSet>,
        state: ProgressionState,
        deload: Boolean,
    ): ProgressionResult {
        if (sets.isEmpty()) return ProgressionResult(state, Change.NONE, "Нет рабочих подходов — без изменений.")
        if (deload) return ProgressionResult(state, Change.NONE, "Разгрузка — прогрессии нет.")

        val weight = sets.maxOf { it.weightKg }
        val history = e1rm(info, sets)?.let { (state.e1rmHistory + it).takeLast(config.progression.e1rmHistory) } ?: state.e1rmHistory
        val timed = info.measure == Measure.SECONDS
        val values = sets.map { if (timed) it.durationSec ?: 0 else it.reps ?: 0 }
        val complete = sets.size >= slot.sets
        val allTop = complete && values.all { it >= slot.repMax } && sets.all { it.rir == null || it.rir >= slot.rirMin }
        val missed = values.any { it < slot.repMin }
        val unit = if (timed) "с" else "повт."

        if (allTop) {
            val next = if (scheme is LoadScheme.NoLoad) null else Loads.nextUp(scheme, weight, minStep(info, scheme, weight))
            if (next != null) {
                return ProgressionResult(
                    ProgressionState(next, 0, history, null), Change.UP,
                    "Все подходы на ${slot.repMax} $unit: дальше ${Fmt.kg(next)} (+${Fmt.num(next - weight)}).",
                )
            }
            val note = stuckNote(info, scheme)
            return ProgressionResult(ProgressionState(weight, 0, history, note), Change.HOLD, note)
        }
        if (missed) {
            val misses = state.missesInRow + 1
            if (misses >= config.progression.missesForReduction && scheme !is LoadScheme.NoLoad && weight > 0.0) {
                val reduced = Loads.roundDown(scheme, weight * (1.0 - config.progression.failureReduction)) ?: weight
                return ProgressionResult(
                    ProgressionState(reduced, 0, history, null), Change.DOWN,
                    "$misses тренировки подряд ниже ${slot.repMin} $unit: вес ${Fmt.kg(weight)} → ${Fmt.kg(reduced)}, набираем заново.",
                )
            }
            return ProgressionResult(
                ProgressionState(weight, misses, history, null), Change.HOLD,
                "Не добрали до ${slot.repMin} $unit: держим ${Fmt.kg(weight)}; ещё раз подряд — вес снизится.",
            )
        }
        return ProgressionResult(
            ProgressionState(weight, 0, history, null), Change.HOLD,
            "Держим ${Fmt.kg(weight)}: добирай ${if (timed) "секунды" else "повторы"} до ${slot.repMax} во всех подходах.",
        )
    }

    /** Line shown under the working scheme, e.g. "+2,5 кг, если все подходы по 8". */
    fun hint(info: ExerciseInfo, scheme: LoadScheme, slot: PlannedSlot, weightKg: Double?): String {
        val unit = if (info.measure == Measure.SECONDS) "с" else ""
        val all = "если все подходы по ${slot.repMax}${if (unit.isEmpty()) "" else " $unit"}"
        if (scheme is LoadScheme.NoLoad || weightKg == null) return "Добавляй ${if (unit.isEmpty()) "повторы до ${slot.repMax}" else "время до ${slot.repMax} с"}, потом — вариант сложнее."
        val next = Loads.nextUp(scheme, weightKg, minStep(info, scheme, weightKg))
            ?: return "${stuckNote(info, scheme)}."
        return when (scheme) {
            is LoadScheme.Implements -> "Следующая ${if (info.equipment.contains("dumbbells")) "пара" else "гиря"} ${Fmt.kg(next)}, $all."
            is LoadScheme.Added -> if (weightKg == 0.0) "Отягощение ${Fmt.kg(next)}, $all." else "+${Fmt.kg(next - weightKg)}, $all."
            else -> "+${Fmt.kg(next - weightKg)}, $all."
        }
    }

    private fun minStep(info: ExerciseInfo, scheme: LoadScheme, weight: Double): Double {
        val step = stepFor(info)
        return when (scheme) {
            is LoadScheme.Barbell -> maxOf(step, Loads.minIncrement(scheme, weight) ?: step)
            is LoadScheme.Stack -> scheme.stepKg
            is LoadScheme.Added -> Loads.minIncrement(scheme, weight) ?: step
            else -> step
        }
    }

    private fun stuckNote(info: ExerciseInfo, scheme: LoadScheme): String = when {
        scheme is LoadScheme.Implements -> "Тяжелее веса нет: добавляй повторы или замедли темп опускания"
        info.load == LoadType.BODYWEIGHT_PLUS -> "Отягощения нет: добавляй повторы, затем усложни вариант"
        scheme is LoadScheme.NoLoad -> "Усложни вариант: резина потуже или дольше пауза"
        else -> "Тяжелее веса в инвентаре нет: добавь блины или повторы"
    }

    /** Best set e1RM for loaded lifts; null for bodyweight, unloaded and timed work. */
    private fun e1rm(info: ExerciseInfo, sets: List<LoggedSet>): Double? {
        if (info.load != LoadType.EXTERNAL || info.measure == Measure.SECONDS) return null
        return sets.filter { (it.reps ?: 0) > 0 && it.weightKg > 0.0 }
            .maxOfOrNull { OneRepMax.estimate(it.weightKg, it.reps!!).average }
    }
}

/** Offers an unplanned deload; the user may decline. */
class DeloadAdvisor(private val config: GeneratorConfig) {

    /**
     * [e1rmHistories] — per compound exercise, oldest first; [hardCheckins] — most recent sessions, oldest first.
     * Returns the reason in Russian, or null when there is nothing to offer.
     */
    fun advise(e1rmHistories: Map<String, List<Double>>, hardCheckins: List<Boolean>, names: Map<String, String> = emptyMap()): String? {
        val drops = config.deload.e1rmDrops
        val falling = e1rmHistories.entries.firstOrNull { (_, h) ->
            h.size > drops && h.takeLast(drops + 1).zipWithNext().all { (a, b) -> b < a * (1.0 - NOISE) }
        }
        if (falling != null) {
            return "Расчётный 1ПМ в «${names[falling.key] ?: falling.key}» падает $drops тренировки подряд."
        }
        val n = config.deload.hardCheckins
        if (hardCheckins.size >= n && hardCheckins.takeLast(n).all { it }) {
            return "Чек-ин «очень тяжело» $n раза подряд."
        }
        return null
    }

    private companion object {
        /** Ignore wobble from rounding to plates. */
        const val NOISE = 0.005
    }
}

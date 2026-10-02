package app.podkhod.domain.plan

import app.podkhod.domain.OneRepMax
import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.busy.StrengthRatio
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.weights.LoadScheme
import app.podkhod.domain.weights.Loads

data class WeightPick(val weightKg: Double?, val reason: String?)

/** What is known about the user's strength, best source first: progression, history, onboarding lifts. */
data class StrengthData(
    val baselines: Map<BaselineLift, Baseline> = emptyMap(),
    val bodyMassKg: Double? = null,
    val ratios: Map<String, StrengthRatio> = emptyMap(),
    /** Recent best e1RM per exercise from logged work sets (external load only). */
    val historyE1rm: Map<String, Double> = emptyMap(),
    /** Current working weight per exercise from progression. */
    val workingWeights: Map<String, Double> = emptyMap(),
)

class StartingWeights(
    private val config: GeneratorConfig,
    private val data: StrengthData,
    private val names: Map<String, String> = emptyMap(),
) {

    private val baselineE1rm: Map<String, Pair<Double, BaselineLift>> = buildMap {
        data.baselines.forEach { (lift, b) ->
            if (b.skipped) return@forEach
            val exercise = config.baselineExercises[lift.code] ?: return@forEach
            val e1rm = b.e1rmOverrideKg ?: run {
                val w = b.weightKg ?: return@forEach
                val r = b.reps ?: return@forEach
                if (r < 1) return@forEach
                if (lift.bodyweight) {
                    val bm = data.bodyMassKg ?: return@forEach
                    OneRepMax.estimate(bm + w, r).average
                } else {
                    OneRepMax.estimate(w, r).average
                }
            }
            put(exercise, e1rm to lift)
        }
    }

    /**
     * Starting weight for [info] at [repMin] reps with [rirMax] in reserve. Bodyweight lifts return the
     * added weight; exercises without load return null weight and no reason.
     */
    fun pick(info: ExerciseInfo, scheme: LoadScheme, repMin: Int, rirMax: Int): WeightPick {
        if (scheme is LoadScheme.NoLoad) return WeightPick(null, null)
        data.workingWeights[info.id]?.let { w ->
            val kg = Loads.roundDown(scheme, w)
            return WeightPick(kg, "Вес ${Fmt.kg(kg)}: текущий рабочий вес из прогрессии.")
        }
        if (info.load == LoadType.BODYWEIGHT_PLUS) return bodyweight(info, scheme, repMin, rirMax)

        data.historyE1rm[info.id]?.let { e1rm ->
            return fromE1rm(e1rm, scheme, repMin, rirMax, "1ПМ ≈ ${Fmt.kg(e1rm)} по твоим последним подходам")
        }
        baselineE1rm[info.id]?.let { (e1rm, _) ->
            return fromE1rm(e1rm, scheme, repMin, rirMax, "1ПМ ≈ ${Fmt.kg(e1rm)} из базового веса")
        }
        familyE1rm(info.id)?.let { (e1rm, source) ->
            return fromE1rm(e1rm, scheme, repMin, rirMax, "1ПМ ≈ ${Fmt.kg(e1rm)} пересчитан от: $source")
        }
        return WeightPick(null, "Вес не задан: нет данных о силе в этом движении — подбери на разминочных подходах.")
    }

    private fun fromE1rm(e1rm: Double, scheme: LoadScheme, repMin: Int, rirMax: Int, source: String): WeightPick {
        val raw = target(e1rm, repMin, rirMax)
        val kg = Loads.roundDown(scheme, raw)
        return WeightPick(kg, "Вес ${Fmt.kg(kg)}: $source; на $repMin повт. с запасом $rirMax — ${Fmt.kg(raw)}, округлено вниз до инвентаря.")
    }

    private fun target(e1rm: Double, reps: Int, rir: Int): Double =
        e1rm / (1.0 + (reps + rir) / 30.0) * config.progression.startFactor

    private fun bodyweight(info: ExerciseInfo, scheme: LoadScheme, repMin: Int, rirMax: Int): WeightPick {
        val own = baselineE1rm[info.id]
        val bm = data.bodyMassKg
        if (own == null || bm == null) {
            return WeightPick(0.0, "Свой вес: прибавляй повторы, отягощение — когда упрёшься в верх диапазона.")
        }
        val added = (target(own.first, repMin, rirMax) - bm).coerceAtLeast(0.0)
        val kg = Loads.roundDown(scheme, added) ?: 0.0
        return WeightPick(kg, if (kg > 0) "Отягощение ${Fmt.kg(kg)} от базового подхода и массы тела." else "Свой вес без отягощения.")
    }

    /** e1RM of [exerciseId] via the strength family of a measured lift. */
    private fun familyE1rm(exerciseId: String): Pair<Double, String>? {
        val mine = data.ratios[exerciseId] ?: return null
        val source = baselineE1rm.entries
            .mapNotNull { (ex, v) -> data.ratios[ex]?.takeIf { it.family == mine.family }?.let { Triple(ex, v, it.ratio) } }
            .maxByOrNull { it.third }
            ?: return null
        val familyE1rm = source.second.first / source.third
        return familyE1rm * mine.ratio to (names[source.first] ?: source.first)
    }
}

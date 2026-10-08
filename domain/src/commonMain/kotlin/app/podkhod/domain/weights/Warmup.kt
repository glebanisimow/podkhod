package app.podkhod.domain.weights

import kotlinx.serialization.Serializable

@Serializable
data class WarmupStep(val weightKg: Double, val repMin: Int, val repMax: Int)

@Serializable
data class WarmupConfig(
    /** Empty bar first, then these fractions of the working weight. */
    val barbellSteps: List<WarmupFraction> = listOf(
        WarmupFraction(0.45, 5, 5),
        WarmupFraction(0.65, 3, 3),
        WarmupFraction(0.82, 1, 2),
    ),
    val emptyBarReps: List<Int> = listOf(8, 10),
    /** Light sets before accessories and isolation, by count chosen in settings (0, 1 or 2). */
    val lightSteps: List<List<WarmupFraction>> = listOf(
        emptyList(),
        listOf(WarmupFraction(0.5, 10, 10)),
        listOf(WarmupFraction(0.4, 10, 10), WarmupFraction(0.7, 5, 5)),
    ),
)

@Serializable
data class WarmupFraction(val fraction: Double, val repMin: Int, val repMax: Int)

object Warmup {
    /**
     * Ramp for a barbell lift: empty bar × 8–10, then ~45 % × 5, ~65 % × 3, ~82 % × 1–2, each rounded to a
     * weight the plates can build. Steps that round to the same weight as the previous one or reach the
     * working weight are dropped.
     */
    fun barbell(workKg: Double, table: PlateTable, config: WarmupConfig = WarmupConfig()): List<WarmupStep> {
        if (workKg <= table.baseKg + EPS) return emptyList()
        val steps = mutableListOf(WarmupStep(table.baseKg, config.emptyBarReps.first(), config.emptyBarReps.last()))
        config.barbellSteps.forEach { f ->
            val kg = Loads.nearest(LoadScheme.Barbell(table), workKg * f.fraction) ?: return@forEach
            if (kg > steps.last().weightKg + EPS && kg < workKg - EPS) steps += WarmupStep(kg, f.repMin, f.repMax)
        }
        return steps
    }

    /** One or two light sets for an accessory; nothing for exercises without a measurable load. */
    fun light(workKg: Double?, scheme: LoadScheme, sets: Int, config: WarmupConfig = WarmupConfig()): List<WarmupStep> {
        if (workKg == null || workKg <= 0.0 || scheme is LoadScheme.NoLoad || scheme is LoadScheme.Added) return emptyList()
        val fractions = config.lightSteps.getOrNull(sets.coerceIn(0, config.lightSteps.lastIndex)).orEmpty()
        val steps = mutableListOf<WarmupStep>()
        fractions.forEach { f ->
            val kg = Loads.nearest(scheme, workKg * f.fraction) ?: return@forEach
            val previous = steps.lastOrNull()?.weightKg ?: 0.0
            if (kg > previous + EPS && kg < workKg - EPS) steps += WarmupStep(kg, f.repMin, f.repMax)
        }
        return steps
    }

    private const val EPS = 1e-6
}

package app.podkhod.domain.weights

import kotlin.math.abs
import kotlin.math.roundToLong

/** [pairs] plates of [weightKg]: one plate of each pair goes on each side of the bar. */
data class PlatePair(val weightKg: Double, val pairs: Int)

/** A loading: [perSide] lists plates from the bar outwards, heaviest first (for a belt: all plates). */
data class PlateLoad(val totalKg: Double, val perSide: List<Double>)

/**
 * [exact] is the loading for the requested weight, if it can be built. Otherwise [lower] and [upper] are
 * the nearest weights below and above that can be built (either may be null at the ends of the range).
 */
data class PlateSolution(val targetKg: Double, val exact: PlateLoad?, val lower: PlateLoad?, val upper: PlateLoad?)

/**
 * Plate math in hundredths of a kilogram, so 1.25 kg plates stay exact. A bounded knapsack over plate
 * types finds every per-side sum the set can build with the fewest plates; greedy loading is not enough
 * for sets like {15, 10} where 20 kg per side needs 10 + 10.
 */
class PlateTable private constructor(
    val baseKg: Double,
    /** Plate type -> how many of it can go on one side. */
    private val types: List<Pair<Double, Int>>,
    private val sides: Int,
) {
    private val weights = types.map { cents(it.first).toInt() }
    private val maxSide = types.indices.sumOf { weights[it] * types[it].second }

    /** best[t][s]: fewest plates using the first t types to reach per-side sum s; -1 if unreachable. */
    private val best: Array<IntArray> = Array(types.size + 1) { IntArray(maxSide + 1) { -1 } }.also { table ->
        table[0][0] = 0
        types.forEachIndexed { t, (_, count) ->
            val w = weights[t]
            val prev = table[t]
            val cur = table[t + 1]
            for (s in 0..maxSide) {
                var bestCount = -1
                var k = 0
                while (k <= count && k * w <= s) {
                    val p = prev[s - k * w]
                    if (p >= 0 && (bestCount < 0 || p + k < bestCount)) bestCount = p + k
                    k++
                }
                cur[s] = bestCount
            }
        }
    }

    val maxTotalKg: Double get() = round2(baseKg + sides * maxSide / 100.0)

    /** Every total weight this set can build, ascending. */
    val totals: List<Double> by lazy {
        val last = best[types.size]
        (0..maxSide).filter { last[it] >= 0 }.map { round2(baseKg + sides * it / 100.0) }
    }

    fun load(totalKg: Double): PlateLoad? {
        val sideCents = (totalKg - baseKg) * 100.0 / sides
        val side = sideCents.roundToLong()
        if (abs(sideCents - side) > 1e-6 || side < 0 || side > maxSide) return null
        if (best[types.size][side.toInt()] < 0) return null
        return PlateLoad(round2(totalKg), reconstruct(side.toInt()))
    }

    fun solve(targetKg: Double): PlateSolution {
        load(targetKg)?.let { return PlateSolution(targetKg, it, null, null) }
        val lower = totals.lastOrNull { it < targetKg - EPS }?.let { load(it) }
        val upper = totals.firstOrNull { it > targetKg + EPS }?.let { load(it) }
        return PlateSolution(targetKg, null, lower, upper)
    }

    private fun reconstruct(side: Int): List<Double> {
        val counts = IntArray(types.size)
        var s = side
        for (t in types.indices.reversed()) {
            val target = best[t + 1][s]
            val w = weights[t]
            var k = 0
            while (k <= types[t].second && k * w <= s) {
                val p = best[t][s - k * w]
                if (p >= 0 && p + k == target) break
                k++
            }
            counts[t] = k
            s -= k * w
        }
        return types.indices.flatMap { t -> List(counts[t]) { types[t].first } }
    }

    companion object {
        private const val EPS = 1e-6

        /** A bar of [barKg] loaded symmetrically. */
        fun barbell(barKg: Double, plates: List<PlatePair>) = PlateTable(barKg, merge(plates) { it.pairs }, sides = 2)

        /** Loose plates on a dip belt or a vest: every single plate counts. */
        fun singles(plates: List<PlatePair>) = PlateTable(0.0, merge(plates) { it.pairs * 2 }, sides = 1)

        private fun merge(plates: List<PlatePair>, count: (PlatePair) -> Int): List<Pair<Double, Int>> = plates
            .filter { it.weightKg > 0.0 && it.pairs > 0 }
            .groupBy { cents(it.weightKg) }
            .map { (c, list) -> c / 100.0 to list.sumOf(count) }
            .sortedByDescending { it.first }

        fun cents(kg: Double): Long = (kg * 100.0).roundToLong()
        fun round2(kg: Double): Double = (kg * 100.0).roundToLong() / 100.0
    }
}

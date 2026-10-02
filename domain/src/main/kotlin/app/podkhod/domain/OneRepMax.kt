package app.podkhod.domain

/**
 * Estimated one-rep max from a submaximal set.
 *
 * Both formulas lose accuracy above ~10 reps; [isReliable] lets the UI flag such estimates.
 * Brzycki is undefined at 37+ reps, so it is capped at [MAX_REPS].
 */
object OneRepMax {
    const val RELIABLE_REPS = 10
    const val MAX_REPS = 30

    fun epley(weightKg: Double, reps: Int): Double {
        require(weightKg >= 0.0) { "weight must be non-negative" }
        require(reps >= 1) { "reps must be at least 1" }
        if (reps == 1) return weightKg
        return weightKg * (1.0 + reps.coerceAtMost(MAX_REPS) / 30.0)
    }

    fun brzycki(weightKg: Double, reps: Int): Double {
        require(weightKg >= 0.0) { "weight must be non-negative" }
        require(reps >= 1) { "reps must be at least 1" }
        if (reps == 1) return weightKg
        val r = reps.coerceAtMost(MAX_REPS)
        return weightKg * 36.0 / (37.0 - r)
    }

    fun estimate(weightKg: Double, reps: Int): Estimate = Estimate(
        epley = epley(weightKg, reps),
        brzycki = brzycki(weightKg, reps),
        isReliable = reps <= RELIABLE_REPS,
    )

    data class Estimate(val epley: Double, val brzycki: Double, val isReliable: Boolean) {
        val average: Double get() = (epley + brzycki) / 2.0
    }
}

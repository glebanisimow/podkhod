package app.podkhod.domain.plan

import app.podkhod.domain.busy.StrengthRatio
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.Loads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeloadAndDiffTest {

    private val advisor = DeloadAdvisor(bundledConfig)

    @Test
    fun e1rmFallingTwiceInARowOffersADeload() {
        val reason = advisor.advise(mapOf("bench" to listOf(100.0, 98.0, 95.0)), emptyList(), mapOf("bench" to "Жим лёжа"))
        assertNotNull(reason)
        assertTrue(reason!!.contains("Жим лёжа"))
    }

    @Test
    fun oneDropOrRoundingWobbleIsNotEnough() {
        assertNull(advisor.advise(mapOf("bench" to listOf(100.0, 102.0, 99.0)), emptyList()))
        assertNull(advisor.advise(mapOf("bench" to listOf(100.0, 99.8, 99.6)), emptyList()))
    }

    @Test
    fun twoVeryHardCheckinsInARowOfferADeload() {
        assertNotNull(advisor.advise(emptyMap(), listOf(false, true, true)))
        assertNull(advisor.advise(emptyMap(), listOf(true, false, true)))
    }

    @Test
    fun diffListsAddedRemovedAndChangedExercises() {
        val old = listOf(ProgramDiff.Day("A", listOf(slot("bench", weight = 80.0), slot("row"))))
        val new = listOf(
            ProgramDiff.Day("A", listOf(slot("bench", sets = 4, weight = 82.5), slot("curl"))),
            ProgramDiff.Day("B", listOf(slot("squat"))),
        )
        val lines = ProgramDiff.diff(old, new, mapOf("bench" to "Жим", "row" to "Тяга", "curl" to "Сгибания", "squat" to "Присед"))
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("убрано — Тяга") })
        assertTrue(lines.any { it.contains("добавлено — Сгибания") })
        assertTrue(lines.any { it.contains("Жим — 3×6–8 → 4×6–8, 80 кг → 82,5 кг") })
        assertTrue(lines.any { it.startsWith("B: новый день") })
        assertTrue(lines.any { it.startsWith("Тренировок в круге: 1 → 2") })
    }

    @Test
    fun sameProgramHasNoDiff() {
        val days = listOf(ProgramDiff.Day("A", listOf(slot("bench"))))
        assertEquals(emptyList<String>(), ProgramDiff.diff(days, days, emptyMap()))
    }

    @Test
    fun startingWeightFromBaselineIsRoundedDownToPlates() {
        val bench = exercise("barbell_bench_press")
        val data = StrengthData(baselines = mapOf(BaselineLift.BENCH to Baseline(80.0, 5)))
        val pick = StartingWeights(bundledConfig, data).pick(bench, Loads.scheme(bench, Inventory()), repMin = 4, rirMax = 2)
        // e1RM = mean(Epley 93.3, Brzycki 90) = 91.7; 91.7 / (1 + 6/30) × 0.95 = 72.6 -> 72.5.
        assertEquals(72.5, pick.weightKg!!, 1e-9)
    }

    @Test
    fun familyRatioConvertsBetweenLifts() {
        val incline = exercise("incline_barbell_bench_press")
        val data = StrengthData(
            baselines = mapOf(BaselineLift.BENCH to Baseline(e1rmOverrideKg = 100.0)),
            ratios = mapOf("barbell_bench_press" to StrengthRatio("chest", 1.0), "incline_barbell_bench_press" to StrengthRatio("chest", 0.8)),
        )
        val pick = StartingWeights(bundledConfig, data).pick(incline, Loads.scheme(incline, Inventory()), repMin = 6, rirMax = 2)
        // 80 / (1 + 8/30) × 0.95 = 60.0
        assertEquals(60.0, pick.weightKg!!, 1e-9)
    }

    @Test
    fun noDataMeansNoGuess() {
        val curl = exercise("curl")
        val pick = StartingWeights(bundledConfig, StrengthData()).pick(curl, Loads.scheme(curl, Inventory()), 10, 1)
        assertNull(pick.weightKg)
        assertNotNull(pick.reason)
    }
}

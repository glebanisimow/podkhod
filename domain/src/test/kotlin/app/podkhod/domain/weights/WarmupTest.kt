package app.podkhod.domain.weights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupTest {

    private val table = PlateTable.barbell(20.0, Inventory.DEFAULT_PLATES)

    @Test
    fun barbellLadderRampsFromEmptyBarToBelowWorkWeight() {
        val steps = Warmup.barbell(100.0, table)
        assertEquals(listOf(20.0, 45.0, 65.0, 82.5), steps.map { it.weightKg })
        assertEquals(8, steps.first().repMin)
        assertEquals(10, steps.first().repMax)
        assertEquals(1, steps.last().repMin)
        assertEquals(2, steps.last().repMax)
    }

    @Test
    fun everyStepIsBuildableAndStrictlyIncreasing() {
        listOf(42.5, 60.0, 87.5, 140.0, 182.5).forEach { work ->
            val weights = Warmup.barbell(work, table).map { it.weightKg }
            assertEquals(weights.sorted().distinct(), weights)
            assertTrue(weights.all { table.load(it) != null && it < work })
        }
    }

    @Test
    fun lightWorkWeightDropsStepsThatCollapse() {
        // 30 kg: 45 % and 65 % both round to the empty bar or 20 kg; only distinct steps remain.
        val weights = Warmup.barbell(30.0, table).map { it.weightKg }
        assertEquals(listOf(20.0, 25.0), weights)
    }

    @Test
    fun emptyBarWorkHasNoWarmup() {
        assertEquals(emptyList<WarmupStep>(), Warmup.barbell(20.0, table))
    }

    @Test
    fun accessoryGetsConfiguredLightSets() {
        val stack = LoadScheme.Stack(5.0)
        assertEquals(emptyList<WarmupStep>(), Warmup.light(50.0, stack, 0))
        assertEquals(listOf(25.0), Warmup.light(50.0, stack, 1).map { it.weightKg })
        assertEquals(listOf(20.0, 35.0), Warmup.light(50.0, stack, 2).map { it.weightKg })
    }

    @Test
    fun bodyweightAndUnloadedAccessoriesHaveNoLightSets() {
        assertEquals(emptyList<WarmupStep>(), Warmup.light(10.0, LoadScheme.Added(listOf(0.0, 10.0)), 1))
        assertEquals(emptyList<WarmupStep>(), Warmup.light(null, LoadScheme.NoLoad, 2))
    }
}

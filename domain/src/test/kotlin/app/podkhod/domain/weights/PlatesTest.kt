package app.podkhod.domain.weights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatesTest {

    private val standard = listOf(
        PlatePair(20.0, 2), PlatePair(10.0, 2), PlatePair(5.0, 2), PlatePair(2.5, 2), PlatePair(1.25, 2),
    )

    @Test
    fun specCase62_5WithTwentyKgBar() {
        val table = PlateTable.barbell(20.0, standard)
        val load = table.load(62.5)
        assertNotNull(load)
        assertEquals(listOf(20.0, 1.25), load!!.perSide)
        assertEquals(62.5, load.totalKg, 1e-9)
    }

    @Test
    fun loadsHeaviestFirstWithFewestPlates() {
        val table = PlateTable.barbell(20.0, standard)
        assertEquals(listOf(20.0, 10.0, 5.0, 2.5, 1.25), table.load(97.5)!!.perSide)
        assertEquals(listOf(20.0, 20.0), table.load(100.0)!!.perSide)
    }

    @Test
    fun emptyBarIsALoadWithNoPlates() {
        val load = PlateTable.barbell(20.0, standard).load(20.0)
        assertEquals(emptyList<Double>(), load!!.perSide)
    }

    @Test
    fun nonStandardSetNeedsSearchNotGreedy() {
        // Greedy would put 15 on each side and get stuck at 50 or 70; 10 + 10 per side builds 60.
        val table = PlateTable.barbell(20.0, listOf(PlatePair(15.0, 1), PlatePair(10.0, 2)))
        assertEquals(listOf(10.0, 10.0), table.load(60.0)!!.perSide)
        assertEquals(listOf(15.0, 10.0), table.load(70.0)!!.perSide)
        assertNull(table.load(65.0))
    }

    @Test
    fun nonStandardBarAndOddPlates() {
        val table = PlateTable.barbell(15.0, listOf(PlatePair(7.5, 2), PlatePair(0.5, 2)))
        assertEquals(listOf(7.5, 0.5), table.load(31.0)!!.perSide)
        assertEquals(47.0, table.maxTotalKg, 1e-9)
    }

    @Test
    fun zeroPairsMeansOnlyTheBar() {
        val table = PlateTable.barbell(20.0, listOf(PlatePair(20.0, 0), PlatePair(10.0, 0)))
        assertEquals(listOf(20.0), table.totals)
        val solution = table.solve(40.0)
        assertNull(solution.exact)
        assertEquals(20.0, solution.lower!!.totalKg, 1e-9)
        assertNull(solution.upper)
    }

    @Test
    fun weightBelowTheBarOffersOnlyTheBarAbove() {
        val solution = PlateTable.barbell(20.0, standard).solve(15.0)
        assertNull(solution.exact)
        assertNull(solution.lower)
        assertEquals(20.0, solution.upper!!.totalKg, 1e-9)
    }

    @Test
    fun unbuildableWeightGivesTwoNearestVariants() {
        val solution = PlateTable.barbell(20.0, standard).solve(63.0)
        assertNull(solution.exact)
        assertEquals(62.5, solution.lower!!.totalKg, 1e-9)
        assertEquals(65.0, solution.upper!!.totalKg, 1e-9)
    }

    @Test
    fun weightAboveTheSetOffersTheMaximumBelow() {
        val table = PlateTable.barbell(20.0, standard)
        val solution = table.solve(200.0)
        assertEquals(table.maxTotalKg, solution.lower!!.totalKg, 1e-9)
        assertNull(solution.upper)
        // Two pairs of each of 20/10/5/2.5/1.25: 2 × 38.75 per side on a 20 kg bar.
        assertEquals(175.0, table.maxTotalKg, 1e-9)
    }

    @Test
    fun duplicatePlateEntriesAreMerged() {
        val table = PlateTable.barbell(20.0, listOf(PlatePair(10.0, 1), PlatePair(10.0, 1)))
        assertEquals(listOf(10.0, 10.0), table.load(60.0)!!.perSide)
    }

    @Test
    fun singlesCountEveryPlateForABelt() {
        val table = PlateTable.singles(listOf(PlatePair(20.0, 1), PlatePair(5.0, 1)))
        assertEquals(listOf(0.0, 5.0, 10.0, 20.0, 25.0, 30.0, 40.0, 45.0, 50.0), table.totals)
        assertEquals(listOf(20.0, 5.0), table.load(25.0)!!.perSide)
    }

    @Test
    fun totalsAreAscendingAndBuildable() {
        val table = PlateTable.barbell(20.0, Inventory.DEFAULT_PLATES)
        val totals = table.totals
        assertEquals(totals.sorted(), totals)
        assertTrue(totals.all { table.load(it) != null })
        assertTrue(62.5 in totals)
    }
}

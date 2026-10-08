package app.podkhod.domain.weights

import app.podkhod.domain.busy.ExerciseInfo
import app.podkhod.domain.busy.Occupancy
import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadsTest {

    private fun info(
        equipment: Set<String>,
        load: LoadType = LoadType.EXTERNAL,
        step: Double = 2.5,
        implements: Int = 1,
    ) = ExerciseInfo(
        id = "x", pattern = MovementPattern.SQUAT, load = load, measure = Measure.REPS, repMin = 5, repMax = 8,
        stepKg = step, occupancy = Occupancy.MEDIUM, equipment = equipment, primary = setOf(Muscle.QUADS),
        implements = implements,
    )

    private val inventory = Inventory(
        plates = listOf(PlatePair(20.0, 2), PlatePair(10.0, 1), PlatePair(5.0, 1), PlatePair(2.5, 1), PlatePair(1.25, 1)),
        kettlebells = listOf(Implement(16.0, 2), Implement(24.0, 1), Implement(32.0, 1)),
        equipment = Inventory.DEFAULT_EQUIPMENT,
    )

    @Test
    fun barbellRoundsToNearestLowerBuildableWeight() {
        val scheme = Loads.scheme(info(setOf("barbell", "rack")), inventory)
        assertEquals(62.5, Loads.roundDown(scheme, 63.9)!!, 1e-9)
        assertEquals(20.0, Loads.roundDown(scheme, 12.0)!!, 1e-9)
    }

    @Test
    fun barbellNextUpRespectsMinimumStepAndPlateSet() {
        val scheme = Loads.scheme(info(setOf("barbell")), inventory)
        assertEquals(65.0, Loads.nextUp(scheme, 62.5, 2.5)!!, 1e-9)
        assertEquals(67.5, Loads.nextUp(scheme, 62.5, 5.0)!!, 1e-9)
        assertEquals(2.5, Loads.minIncrement(scheme, 60.0)!!, 1e-9)
    }

    @Test
    fun barbellWithoutSmallPlatesJumpsByTheSmallestPair() {
        val heavyOnly = inventory.copy(plates = listOf(PlatePair(20.0, 2), PlatePair(10.0, 2)))
        val scheme = Loads.scheme(info(setOf("barbell")), heavyOnly)
        assertEquals(20.0, Loads.minIncrement(scheme, 60.0)!!, 1e-9)
        assertEquals(80.0, Loads.nextUp(scheme, 60.0, 2.5)!!, 1e-9)
    }

    @Test
    fun doubleKettlebellLiftsNeedTwoBellsOfTheSameWeight() {
        val single = Loads.scheme(info(setOf("kettlebell")), inventory) as LoadScheme.Implements
        val double = Loads.scheme(info(setOf("kettlebell"), implements = 2), inventory) as LoadScheme.Implements
        assertEquals(listOf(16.0, 24.0, 32.0), single.weightsKg)
        assertEquals(listOf(16.0), double.weightsKg)
    }

    @Test
    fun kettlebellNextUpIsTheNextBell() {
        val scheme = Loads.scheme(info(setOf("kettlebell")), inventory)
        assertEquals(24.0, Loads.nextUp(scheme, 16.0, 2.5)!!, 1e-9)
        assertNull(Loads.nextUp(scheme, 32.0, 2.5))
        assertEquals(16.0, Loads.roundDown(scheme, 20.0)!!, 1e-9)
    }

    @Test
    fun machineStackUsesCatalogStep() {
        val scheme = Loads.scheme(info(setOf("lat_pulldown"), step = 5.0), inventory)
        assertEquals(45.0, Loads.roundDown(scheme, 48.0)!!, 1e-9)
        assertEquals(50.0, Loads.nextUp(scheme, 45.0, 2.5)!!, 1e-9)
        assertEquals(5.0, Loads.roundDown(scheme, 1.0)!!, 1e-9)
    }

    @Test
    fun pullUpsCarryPlatesOnlyWithABelt() {
        val pullup = info(setOf("pullup_bar"), load = LoadType.BODYWEIGHT_PLUS)
        val noBelt = Loads.scheme(pullup, inventory) as LoadScheme.Added
        assertEquals(listOf(0.0), noBelt.optionsKg)
        assertNull(Loads.nextUp(noBelt, 0.0, 2.5))

        val withBelt = Loads.scheme(pullup, inventory.copy(equipment = inventory.equipment + "belt_chain"))
        assertEquals(1.25, Loads.nextUp(withBelt, 0.0, 1.25)!!, 1e-9)
        assertEquals(10.0, Loads.roundDown(withBelt, 11.0)!!, 1e-9)
    }

    @Test
    fun noLoadHasNoWeights() {
        val scheme = Loads.scheme(info(setOf("bands"), load = LoadType.NONE), inventory)
        assertEquals(LoadScheme.NoLoad, scheme)
        assertNull(Loads.roundDown(scheme, 10.0))
    }

    @Test
    fun emptyKettlebellListRemovesKettlebellFromAvailableEquipment() {
        val none = inventory.copy(kettlebells = emptyList())
        assertTrue("kettlebell" !in none.available)
        assertTrue("dumbbells" !in Inventory().available)
    }
}

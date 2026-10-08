package app.podkhod.domain.plan

import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import app.podkhod.domain.weights.Implement
import app.podkhod.domain.weights.Inventory
import app.podkhod.domain.weights.Loads
import app.podkhod.domain.weights.PlatePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {

    private val progression = Progression(bundledConfig)
    private val inventory = Inventory()
    private val bench = exercise("bench")
    private val squat = exercise("squat", pattern = MovementPattern.SQUAT, equipment = setOf("barbell", "rack"), primary = setOf(Muscle.QUADS))

    private fun sets(weight: Double, vararg reps: Int, rir: Int? = 1) = reps.map { LoggedSet(weight, it, rir = rir) }

    private fun update(info: app.podkhod.domain.busy.ExerciseInfo, s: List<LoggedSet>, state: ProgressionState = ProgressionState(80.0), inv: Inventory = inventory, deload: Boolean = false) =
        progression.update(info, Loads.scheme(info, inv), slot(info.id), s, state, deload)

    @Test
    fun allSetsAtTopAddTwoAndAHalfForPresses() {
        val r = update(bench, sets(80.0, 8, 8, 8))
        assertEquals(Change.UP, r.change)
        assertEquals(82.5, r.state.workingWeightKg!!, 1e-9)
    }

    @Test
    fun squatsAndPullsGoUpByFive() {
        val r = update(squat, sets(100.0, 8, 8, 8), ProgressionState(100.0))
        assertEquals(105.0, r.state.workingWeightKg!!, 1e-9)
    }

    @Test
    fun stepNeverGoesBelowWhatThePlatesAllow() {
        // Pairs of 20 and 5 build 80 and 100 but nothing in between.
        val heavyPlates = Inventory(plates = listOf(PlatePair(20.0, 2), PlatePair(5.0, 2)))
        val r = update(bench, sets(80.0, 8, 8, 8), inv = heavyPlates)
        assertEquals(100.0, r.state.workingWeightKg!!, 1e-9)
    }

    @Test
    fun reserveBelowTargetBlocksProgression() {
        val r = update(bench, sets(80.0, 8, 8, 8, rir = 0))
        assertEquals(Change.HOLD, r.change)
        assertEquals(80.0, r.state.workingWeightKg!!, 1e-9)
    }

    @Test
    fun unratedSetsCountAsOnTarget() {
        assertEquals(Change.UP, update(bench, sets(80.0, 8, 8, 8, rir = null)).change)
    }

    @Test
    fun fewerSetsThanPlannedDoNotProgress() {
        assertEquals(Change.HOLD, update(bench, sets(80.0, 8, 8)).change)
    }

    @Test
    fun oneMissHoldsTwoMissesInARowCutTenPercent() {
        val first = update(bench, sets(80.0, 6, 5, 5))
        assertEquals(Change.HOLD, first.change)
        assertEquals(1, first.state.missesInRow)
        val second = update(bench, sets(80.0, 6, 5, 4), first.state)
        assertEquals(Change.DOWN, second.change)
        // 80 × 0.9 = 72, rounded down to what the plates build.
        assertEquals(70.0, second.state.workingWeightKg!!, 1e-9)
        assertEquals(0, second.state.missesInRow)
    }

    @Test
    fun aNormalSessionResetsTheMissCounter() {
        val r = update(bench, sets(80.0, 7, 6, 6), ProgressionState(80.0, missesInRow = 1))
        assertEquals(Change.HOLD, r.change)
        assertEquals(0, r.state.missesInRow)
    }

    @Test
    fun kettlebellGoesToTheNextBellAndStopsAtTheHeaviest() {
        val swing = exercise("press", pattern = MovementPattern.V_PUSH, equipment = setOf("kettlebell"), primary = setOf(Muscle.DELTS_FRONT))
        val inv = Inventory(kettlebells = listOf(Implement(16.0, 1), Implement(24.0, 1)))
        val up = update(swing, sets(16.0, 8, 8, 8), ProgressionState(16.0), inv)
        assertEquals(24.0, up.state.workingWeightKg!!, 1e-9)
        val stuck = update(swing, sets(24.0, 8, 8, 8), ProgressionState(24.0), inv)
        assertEquals(Change.HOLD, stuck.change)
        assertNotNull(stuck.state.note)
    }

    @Test
    fun bodyweightWithoutBeltSuggestsRepsOrAHarderVariant() {
        val pullup = exercise("pullup", pattern = MovementPattern.V_PULL, equipment = setOf("pullup_bar"), primary = setOf(Muscle.LATS), load = LoadType.BODYWEIGHT_PLUS)
        val r = update(pullup, sets(0.0, 8, 8, 8), ProgressionState(0.0))
        assertEquals(Change.HOLD, r.change)
        assertTrue(r.message, r.message.contains("усложни"))
        val belt = update(pullup, sets(0.0, 8, 8, 8), ProgressionState(0.0), inventory.copy(equipment = inventory.equipment + "belt_chain"))
        assertEquals(Change.UP, belt.change)
        assertEquals(1.25, belt.state.workingWeightKg!!, 1e-9)
    }

    @Test
    fun deloadSessionsDoNotProgress() {
        assertEquals(Change.NONE, update(bench, sets(72.5, 8, 8, 8), deload = true).change)
    }

    @Test
    fun e1rmHistoryKeepsTheLastFive() {
        var state = ProgressionState(80.0)
        repeat(7) { state = update(bench, sets(80.0, 7, 7, 7), state).state }
        assertEquals(bundledConfig.progression.e1rmHistory, state.e1rmHistory.size)
    }

    @Test
    fun hintNamesTheNextStep() {
        val scheme = Loads.scheme(bench, inventory)
        assertEquals("+2,5 кг, если все подходы по 8.", progression.hint(bench, scheme, slot("bench"), 80.0))
    }
}

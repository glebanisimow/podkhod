package app.podkhod.domain.busy

import app.podkhod.domain.model.LoadType
import app.podkhod.domain.model.Measure
import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusyQueueTest {

    private fun slot(id: Long, ex: String, group: String? = null, done: Boolean = false) = QueueSlot(id, ex, group, done)

    @Test
    fun laterMovesSlotToEndKeepingTheRestInOrder() {
        val queue = listOf(slot(1, "a", done = true), slot(2, "b"), slot(3, "c"), slot(4, "d"))
        val r = BusyQueue.moveToEnd(queue, 2)
        assertEquals(listOf(1L, 3L, 4L, 2L), r.order)
        assertEquals(3L, r.nextId)
    }

    @Test
    fun laterMovesWholeSuperset() {
        val queue = listOf(slot(1, "a"), slot(2, "b", "A"), slot(3, "c", "A"), slot(4, "d"))
        val r = BusyQueue.moveToEnd(queue, 3)
        assertEquals(listOf(1L, 4L, 2L, 3L), r.order)
        assertEquals(4L, r.nextId)
    }

    @Test
    fun laterOnLastRemainingSlotStaysOnIt() {
        val queue = listOf(slot(1, "a", done = true), slot(2, "b"))
        val r = BusyQueue.moveToEnd(queue, 2)
        assertEquals(listOf(1L, 2L), r.order)
        assertEquals(2L, r.nextId)
    }

    @Test
    fun laterSkipsFinishedSlotsAndWrapsAround() {
        val queue = listOf(slot(1, "a"), slot(2, "b"), slot(3, "c", done = true))
        val r = BusyQueue.moveToEnd(queue, 2)
        assertEquals(listOf(1L, 3L, 2L), r.order)
        assertEquals(1L, r.nextId)
    }

    private fun info(id: String, equipment: Set<String>) = ExerciseInfo(
        id, MovementPattern.SQUAT, LoadType.EXTERNAL, Measure.REPS, 6, 12, 2.5, Occupancy.LOW, equipment, setOf(Muscle.QUADS),
    )

    private val catalog = listOf(
        info("squat", setOf("barbell", "rack")),
        info("ohp", setOf("barbell", "rack")),
        info("row", setOf("barbell")),
        info("pull_up", setOf("pullup_bar")),
        info("plank", emptySet()),
        info("dead_hang", setOf("pullup_bar")),
        info("db_curl", setOf("dumbbells")),
    ).associateBy { it.id }

    private val config = SubstitutionConfig(
        stations = setOf("rack"),
        unavailableEquipment = setOf("dumbbells"),
        sessionMuscleCap = 8,
        safetyFactor = 0.9,
        ratios = emptyMap(),
        waitExtras = listOf("db_curl", "plank", "dead_hang", "pull_up"),
    )

    @Test
    fun waitingSuggestsNearestSlotWithoutTheBusyStation() {
        val queue = listOf(slot(1, "squat"), slot(2, "ohp"), slot(3, "row"), slot(4, "pull_up"))
        val plan = BusyQueue.whileWaiting(queue, 1, catalog, config)
        assertEquals(3L, plan.slotId)
        // pull_up is already in the session, db_curl needs missing dumbbells.
        assertEquals(listOf("plank", "dead_hang"), plan.extras)
    }

    @Test
    fun waitingSkipsDoneSlotsAndOwnSuperset() {
        val queue = listOf(slot(1, "row", "A"), slot(2, "pull_up", "A"), slot(3, "plank", done = true))
        val plan = BusyQueue.whileWaiting(queue, 1, catalog, config)
        assertNull(plan.slotId)
    }
}

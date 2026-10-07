package app.podkhod.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldTimerTest {

    private val t0 = 1_000_000L
    private val timer = HoldTimer(slotId = 1, targetSec = 45, startedAt = t0)

    @Test
    fun getReadyCountdownComesFirst() {
        assertEquals(HoldTimer.Phase.Prep(5), timer.phase(t0))
        assertEquals(HoldTimer.Phase.Prep(1), timer.phase(t0 + 4_500))
    }

    @Test
    fun holdCountsDownToTheTarget() {
        val start = t0 + 5_000
        assertEquals(45, (timer.phase(start) as HoldTimer.Phase.Hold).leftSec)
        val mid = timer.phase(start + 22_500) as HoldTimer.Phase.Hold
        assertEquals(23, mid.leftSec)
        assertEquals(0.5f, mid.progress, 0.001f)
        assertEquals(HoldTimer.Phase.Done, timer.phase(start + 45_000))
    }

    @Test
    fun stoppingEarlyLogsWhatWasHeld() {
        val start = t0 + 5_000
        assertNull(timer.heldSec(t0 + 2_000))
        assertEquals(0, timer.heldSec(start + 400))
        assertEquals(31, timer.heldSec(start + 31_900))
        assertEquals(45, timer.heldSec(start + 60_000))
    }
}

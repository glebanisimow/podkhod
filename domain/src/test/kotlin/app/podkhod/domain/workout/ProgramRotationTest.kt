package app.podkhod.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramRotationTest {

    @Test
    fun loopsOverDays() {
        assertEquals(1, ProgramRotation.next(0, 2))
        assertEquals(0, ProgramRotation.next(1, 2))
        assertEquals(2, ProgramRotation.next(1, 3))
    }

    @Test
    fun finishingOffRotationDayResyncsPointer() {
        // Pointer said B (1) but the user did A (0): the next one is B again.
        assertEquals(1, ProgramRotation.afterFinish(0, 3))
        assertEquals(0, ProgramRotation.afterFinish(2, 3))
    }

    @Test
    fun outOfRangePointerIsNormalized() {
        assertEquals(1, ProgramRotation.normalize(4, 3))
        assertEquals(2, ProgramRotation.normalize(-1, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyProgramIsRejected() {
        ProgramRotation.next(0, 0)
    }
}

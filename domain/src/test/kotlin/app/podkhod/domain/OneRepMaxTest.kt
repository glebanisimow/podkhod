package app.podkhod.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneRepMaxTest {

    @Test
    fun singleRepIsTheWeightItself() {
        assertEquals(100.0, OneRepMax.epley(100.0, 1), 1e-9)
        assertEquals(100.0, OneRepMax.brzycki(100.0, 1), 1e-9)
    }

    @Test
    fun epleyMatchesReferenceValue() {
        // 100 kg x 5: 100 * (1 + 5/30) = 116.67
        assertEquals(116.6667, OneRepMax.epley(100.0, 5), 1e-3)
    }

    @Test
    fun brzyckiMatchesReferenceValue() {
        // 100 kg x 5: 100 * 36 / 32 = 112.5
        assertEquals(112.5, OneRepMax.brzycki(100.0, 5), 1e-9)
    }

    @Test
    fun formulasAgreeAtTenReps() {
        val e = OneRepMax.estimate(80.0, 10)
        assertEquals(e.epley, e.brzycki, 0.5)
        assertTrue(e.isReliable)
    }

    @Test
    fun highRepEstimatesAreFlaggedAndBrzyckiStaysFinite() {
        val e = OneRepMax.estimate(40.0, 50)
        assertFalse(e.isReliable)
        assertTrue(e.brzycki.isFinite())
        assertTrue(e.brzycki > 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroRepsIsRejected() {
        OneRepMax.epley(100.0, 0)
    }
}

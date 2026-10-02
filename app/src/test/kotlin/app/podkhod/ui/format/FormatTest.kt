package app.podkhod.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {

    @Test
    fun weightUsesDecimalCommaAndDropsTrailingZeros() {
        assertEquals("60", Format.weight(60.0))
        assertEquals("62,5", Format.weight(62.5))
        assertEquals("1,25", Format.weight(1.25))
        assertEquals("0", Format.weight(0.0))
    }

    @Test
    fun parseWeightAcceptsCommaAndDot() {
        assertEquals(62.5, Format.parseWeight("62,5")!!, 1e-9)
        assertEquals(62.5, Format.parseWeight(" 62.5 ")!!, 1e-9)
        assertNull(Format.parseWeight("abc"))
        assertNull(Format.parseWeight("-5"))
    }

    @Test
    fun clockAndRest() {
        assertEquals("1:35", Format.clock(95))
        assertEquals("1:02:05", Format.clock(3725))
        assertEquals("0:00", Format.clock(-3))
        assertEquals("45 с", Format.rest(45))
        assertEquals("2 мин", Format.rest(120))
        assertEquals("1:30", Format.rest(90))
    }

    @Test
    fun durationAndVolume() {
        assertEquals("48 мин", Format.duration(48 * 60L))
        assertEquals("1 ч 05 мин", Format.duration(65 * 60L))
        assertEquals("12\u202F345", Format.volume(12_345.4))
        assertEquals("+1\u202F200 кг", Format.signedVolume(1200.0))
        assertEquals("−50 кг", Format.signedVolume(-50.0))
    }

    @Test
    fun setResult() {
        assertEquals("60 × 8", Format.setResult(60.0, 8, null))
        assertEquals("12", Format.setResult(0.0, 12, null))
        assertEquals("45 с", Format.setResult(null, null, 45))
    }
}

package app.dsh.sugar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBubbleFirstPassTest {
    private fun plan(chars: Int, scale: Int = 1, rtl: Boolean = false, comma: Int = 0): PetBubbleFirstPass {
        val width = 20 * scale
        val carets = FloatArray(chars + 1) { (if (rtl) chars - it else it) * width.toFloat() }
        val pauses = LongArray(chars + 1)
        if (comma > 0) pauses[comma] = 90L
        return PetBubbleFirstPass(carets, pauses, 3 * width, chars * width, width.toFloat())
    }

    @Test fun headTypesQuicklyAndFirstViewportStaysStillForOneSecond() {
        assertEquals(96L, plan(3).totalMs)
        val reading = plan(20)
        assertEquals(3, reading.shownAt(96))
        assertEquals(0f, reading.offsetAt(3, 1096), .001f)
        assertEquals(10f, reading.offsetAt(3, 1196), .001f)
        assertEquals(4, reading.shownAt(1296))
    }

    @Test fun fiveFullWidthCharactersPerSecondIndependentOfLengthAndFontScale() {
        val short = plan(10)
        val long = plan(402)
        assertEquals(1_000L, short.reveals[9] - short.reveals[4])
        assertEquals(short.reveals[9], long.reveals[9])
        assertEquals(long.totalMs, plan(402, scale = 3).totalMs)
        assertEquals(402, long.shownAt(long.totalMs))
        assertEquals((402 - 3) * 20f, long.offsetAt(402, long.totalMs), .001f)
    }

    @Test fun punctuationHoldsBeforeMovingAndRightToLeftMirrorsTheRead() {
        val plain = plan(12)
        val paused = plan(12, comma = 5)
        assertEquals(90L, paused.totalMs - plain.totalMs)
        assertEquals(paused.offsets[5], paused.offsetAt(5, paused.reveals[5] + 89L), .001f)
        val rtl = plan(12, rtl = true)
        val limit = rtl.offsets[0]
        assertTrue(limit > 0f)
        assertEquals(plain.totalMs, rtl.totalMs)
        for (t in 0L..rtl.totalMs step 17) {
            val i = rtl.shownAt(t)
            assertEquals(plain.shownAt(t), i)
            assertEquals(limit - plain.offsetAt(i, t), rtl.offsetAt(i, t), .001f)
        }
    }
}

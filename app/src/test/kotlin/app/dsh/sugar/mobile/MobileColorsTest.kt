package app.dsh.sugar.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileColorsTest {
    private val pixels = intArrayOf(0xff000000.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt(),
                                    0xff0000ff.toInt(), 0xffffffff.toInt(), 0xff111111.toInt())

    private fun read(points: List<Pair<Int,Int>>, radius: Int): List<Int> =
        MobileColors.sample(3, 2, points, radius) { left, top, width, height, buffer ->
            for (row in 0 until height) {
                val start = (row + top) * 3 + left
                pixels.copyInto(buffer, row * width, start, start + width)
            }
        }

    @Test fun exactRgbPreservesOrderAndRepeatedPoints() {
        assertEquals(listOf(0x00ff00,0x0000ff,0x00ff00), read(listOf(2 to 0,0 to 1,2 to 0), 0))
    }

    @Test fun clipsAtEdgesAndRoundsEachChannelIndependently() {
        assertEquals(listOf(0x804080,0x585858), read(listOf(0 to 0,1 to 1), 1))
        assertEquals(listOf(0x585858), read(listOf(2 to 0), MobileColors.MAX_RADIUS))
    }
}

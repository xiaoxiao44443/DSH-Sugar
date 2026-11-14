package app.dsh.sugar

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarBackdropColorTest {
    private val columns = SidebarBackdropColor.COLUMNS
    private val rows = SidebarBackdropColor.ROWS

    private fun strip(color: (x: Int, y: Int) -> Int) =
        IntArray(columns * rows) { i -> color(i % columns, i / columns) }

    private fun maxChannelDifference(first: Int, second: Int) =
        listOf(16, 8, 0).maxOf { shift ->
            abs((first ushr shift and 255) - (second ushr shift and 255))
        }

    @Test fun solidSurfacesKeepTheirOriginalRgb() {
        for (color in listOf(0, 0xffffff, 0x010303, 0x773e69, 0xee1166)) {
            val bitmap = strip { _, _ -> color or 0xff000000.toInt() }
            assertEquals(color, SidebarBackdropColor.representative(bitmap, true))
            assertEquals(color, SidebarBackdropColor.representative(bitmap, false))
        }
    }

    @Test fun darkTextAndThinHighlightsHaveLittleInfluenceOnTheirPage() {
        for ((page, ink) in listOf(0xe4d8cb to 0x191d22, 0x193c51 to 0xfafaf7)) {
            val text = strip { x, y -> if ((x + y) % 8 == 0) ink else page }
            val result = SidebarBackdropColor.representative(text, true)
            assertTrue("Sparse text shifted the page to ${result.toString(16)}",
                maxChannelDifference(page, result) <= 8)
        }
    }

    @Test fun equallyLightColorsMixAndChangingOneRowDoesNotPickAWinner() {
        // The swatches have Oklab L of 0.5473 and 0.5475, with opposing a and b coordinates.
        val purple = 0x975494
        val green = 0x278362
        fun mixed(split: Int) = SidebarBackdropColor.representative(
            strip { _, y -> if (y < split) purple else green }, true)
        val balanced = mixed(rows / 2)
        assertTrue(maxChannelDifference(0x6f707c, balanced) <= 1)
        assertTrue(maxChannelDifference(balanced, mixed(rows / 2 - 1)) <= 12)
        assertTrue(maxChannelDifference(balanced, mixed(rows / 2 + 1)) <= 12)
        val reversed = strip { _, y -> if (y < rows / 2) green else purple }
        assertEquals(balanced, SidebarBackdropColor.representative(reversed, true))
    }

    @Test fun bothEndsOfAVerticalGradientContribute() {
        fun gradient(low: Int, high: Int) = SidebarBackdropColor.representative(strip { _, y ->
            val gray = low + (high - low) * y / (rows - 1)
            gray * 0x010101
        }, false) and 255
        val dark = gradient(0, 140)
        val full = gradient(0, 255)
        val light = gradient(110, 255)
        assertTrue(full in 100..145)
        assertTrue(full - dark > 30)
        assertTrue(light - full > 30)
    }

    @Test fun nearColumnsInfluenceBothDockPositionsSymmetrically() {
        val near = 0xb03b56
        val far = 0x3e8985
        val sample = strip { x, _ -> if (x < columns / 2) near else far }
        val mirror = strip { x, _ -> if (x >= columns / 2) near else far }
        val left = SidebarBackdropColor.representative(sample, true)
        val right = SidebarBackdropColor.representative(sample, false)
        assertEquals(left, SidebarBackdropColor.representative(mirror, false))
        assertEquals(right, SidebarBackdropColor.representative(mirror, true))
        assertTrue(maxChannelDifference(left, near) < maxChannelDifference(right, near))
        assertTrue(maxChannelDifference(right, far) < maxChannelDifference(left, far))
    }
}

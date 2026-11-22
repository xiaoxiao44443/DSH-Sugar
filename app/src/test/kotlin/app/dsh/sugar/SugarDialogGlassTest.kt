package app.dsh.sugar

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

class SugarDialogGlassTest {
    @Test fun whiteAndDarkGlassAndNoBlurFallback() {
        val white = SugarGlassMaterial(0xffffffff.toInt(), false)
        val tint = white.modalFill(0xff080c11.toInt(), true, .9)
        assertTrue((tint ushr 24) in 165..175)
        for (shift in intArrayOf(0, 8, 16)) assertTrue(((tint ushr shift) and 255) >= 253)
        assertTrue((white.modalFill(0xff080c11.toInt(), false, .9) ushr 24) in 201..213)
        val dark = SugarGlassMaterial(0xff16181b.toInt(), true)
        assertTrue((dark.tint and 255) < 40)
        assertTrue((dark.buttonFill(dark.accent(), true) ushr 24) >
            (dark.buttonFill(dark.accent(true), false) ushr 24))
    }

    @Test fun expandedFrameScalesWithDensityAndAdaptsToPageBrightness() {
        val white = SugarGlassMaterial(-1, false)
        val dark = SugarGlassMaterial(0xff293448.toInt(), true)
        assertEquals(listOf(1, 2, 3, 4), listOf(.75f, 1f, 2f, 2.6f).map(white::frameWidthPx))
        assertEquals(listOf(1, 1, 2, 3), listOf(.75f, 1f, 2f, 2.6f).map(dark::frameWidthPx))
        val charcoal = 0xff25272c.toInt()
        val stroke = SugarGlassMaterial(charcoal, true).frameEdge
        val blended = SugarGlassMaterial.blend(charcoal, stroke, (stroke ushr 24) / 255f)
        assertTrue(((blended ushr 16) and 255) in 96..102)
        assertTrue(((blended ushr 8) and 255) in 98..105)
        assertTrue((blended and 255) in 105..111)
        assertEquals(0xffffff, white.frameEdge and 0xffffff)
        assertTrue((white.frameEdge ushr 24) in 232..245)
        assertTrue((dark.frameEdge ushr 24) in 125..142)
        assertTrue((white.badgeEdge ushr 24) < (dark.frameEdge ushr 24))
    }

    @Test fun darkModalRemainsReadableOverWhiteInput() {
        verifyContrast(0xff293448.toInt(), 0xfffffefb.toInt(), -1, dark = true)
    }

    @Test fun lightModalSubduesUnderlyingControlsAndStillTransmitsPastelColor() {
        val material = SugarGlassMaterial(-1, false)
        val fill = material.modalFill(0xff080c11.toInt(), true, .9)
        val blue = 0xffc6e2ff.toInt()
        val mixed = SugarGlassMaterial.blend(blue, fill, (fill ushr 24) / 255f)
        // A broad pastel patch should still be discernible beneath the panel, with reduced contrast.
        assertTrue(((mixed and 255) - ((mixed ushr 16) and 255)) in 15..24)
        verifyContrast(-1, 0xff080c11.toInt(), blue, dark = false)
        verifyContrast(-1, 0xff080c11.toInt(), 0xff000000.toInt(), dark = false)
    }

    @Test fun materialKeepsColorAndTransitionsContinuouslyAcrossMidTones() {
        val green = SugarGlassMaterial(0xff82ceb0.toInt(), false)
        val tint = green.modalTint
        assertTrue(((tint ushr 8) and 255) - ((tint ushr 16) and 255) > 60)
        val dark = SugarGlassMaterial(0xff293448.toInt(), true)
        assertTrue((dark.modalFill(0xfffffefb.toInt(), true, .9) ushr 24) in 198..219)
        assertEquals(18.0, dark.modalBlurDp, .001)
        assertEquals(22.0, SugarGlassMaterial(-1, false).modalBlurDp, .001)
        var previous = 0
        for (gray in 20..245) {
            val m = SugarGlassMaterial(0xff000000.toInt() or (gray * 0x010101), gray < 155)
            val alpha = m.modalFill(if (gray < 115) -1 else 0xff080c11.toInt(), true, .9) ushr 24
            if (previous != 0) assertTrue("Color step $gray: $previous to $alpha", kotlin.math.abs(previous - alpha) <= 3)
            previous = alpha
        }
    }

    private fun verifyContrast(surface: Int, ink: Int, underlay: Int, dark: Boolean) {
        val material = SugarGlassMaterial(surface, dark)
        val fill = material.modalFill(ink, true, .90)
        assertTrue((fill ushr 24) < 255)
        fun composite(bottom: Int, top: Int): Int {
            val a = (top ushr 24) / 255.0
            fun channel(shift: Int): Int {
                val bg = (bottom ushr shift) and 255
                val fg = (top ushr shift) and 255
                return (bg * (1 - a) + fg * a).roundToInt() shl shift
            }
            return 0xff000000.toInt() or channel(16) or channel(8) or channel(0)
        }
        fun level(color: Int): Double {
            fun channel(shift: Int): Double {
                val v = ((color ushr shift) and 255) / 255.0
                return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        fun check(bottom: Int) {
            val ratio = (maxOf(level(bottom), level(ink)) + .05) / (minOf(level(bottom), level(ink)) + .05)
            assertTrue("Expected at least 4.5:1 after 8-bit rounding, got $ratio", ratio >= 4.5)
        }
        val panel = composite(underlay, fill)
        check(panel)
        for (primary in listOf(true, false)) {
            val tone = material.accent(!primary)
            val button = composite(panel, material.buttonFill(tone, primary, material.modalTint))
            check(button)
            check(composite(button, (tone and 0x00ffffff) or 0x30000000))
        }
    }

    @Test fun previewCanUseTheSpaceBelowTheCardForTheIllustration() {
        // The nearly full-height landscape preview leaves 29dp in total: clip, do not shrink.
        val top = dialogPeekHeadroom(29, cap = 34, preview = true)
        val peek = dialogPeekBounds(0, top, 210, 0, 0, 210, 1f, true, preview = true)!!
        assertEquals(38, peek.bottom - peek.top)
        assertEquals(-5, peek.top)
        assertEquals(33, peek.bottom)
        assertEquals(14, dialogPeekHeadroom(29, cap = 34, preview = false))
        assertEquals(34, dialogPeekHeadroom(80, cap = 34, preview = true))
        assertEquals(0, dialogPeekHeadroom(0, cap = 34, preview = true))
    }

    @Test fun draggedPreviewKeepsTheMascotAndPaddingUnchangedAtBothEdges() {
        for (density in listOf(1f, 2.55f)) for (compact in listOf(false, true)) {
            fun dp(value: Int) = (value * density).roundToInt()
            val cap = dp(if (compact) 34 else 57)
            val spare = cap * 4
            val initial = previewVerticalPlacement(spare, cap, requestedShift = 0)
            val figure = dialogPeekBounds(0, initial.top, dp(370), 0, 0, dp(370), density, compact, preview = true)!!
            for (shift in -spare..spare) {
                val moved = previewVerticalPlacement(spare, cap, requestedShift = shift)
                assertEquals(initial.top, moved.top)
                assertEquals(initial.bottom, moved.bottom)
                assertEquals(figure, dialogPeekBounds(0, moved.top, dp(370), 0, 0, dp(370), density, compact, preview = true))
                assertTrue(figure.top >= -1) // Pixel rounding at non-integer densities.
            }
        }
    }

    @Test fun previewMascotStillFitsNarrowWidthsAndOrdinaryDialogsStillFitHeadroom() {
        val narrow = dialogPeekBounds(0, 57, 100, 0, 0, 100, 1f, false, preview = true)!!
        val moved = dialogPeekBounds(0, 10, 100, 0, 0, 100, 1f, false, preview = true)!!
        assertTrue(narrow.bottom - narrow.top < 61)
        assertTrue(narrow.left >= 10 && narrow.right <= 88)
        assertEquals(narrow.right - narrow.left, moved.right - moved.left)
        assertEquals(narrow.bottom - narrow.top, moved.bottom - moved.top)

        val ordinary = dialogPeekBounds(0, 20, 370, 0, 0, 370, 1f, false)!!
        assertEquals(24, ordinary.bottom - ordinary.top)
        assertEquals(0, ordinary.top)
        assertNull(dialogPeekBounds(0, 8, 370, 0, 0, 370, 1f, false))
    }

    @Test fun peekStaysAboveDialogAndOnItsSideOfTheRail() {
        val bounds = dialogPeekBounds(70, 260, 370, 0, 27, 390, 1f, false)!!
        assertTrue(bounds.left >= 70 && bounds.right <= 370)
        assertEquals(264, bounds.bottom)
        assertEquals(61, bounds.bottom - bounds.top)
        val smaller = dialogPeekBounds(100, 53, 280, 10, 35, 720, 1f, true)!!
        assertEquals(35, smaller.top)
        assertTrue(smaller.bottom < 60)
    }

    @Test fun previewDragStopsTheWholeWindowAtSafeEdgesWithoutRelayout() {
        for (spare in listOf(0, 23, 58, 96, 271)) {
            val centered = previewVerticalPlacement(spare, cap = 34, requestedShift = 0)
            val travel = spare - centered.top - centered.bottom
            val start = travel / 2
            assertEquals(0, centered.windowY)
            var previousTop = 0
            for (dy in -300..300) {
                val p = previewVerticalPlacement(spare, cap = 34, requestedShift = dy)
                val windowTop = start + p.windowY
                assertEquals((start + dy).coerceIn(0, travel), windowTop)
                assertEquals(p.shift, p.windowY)
                assertEquals(centered.top, p.top)
                assertEquals(centered.bottom, p.bottom)
                assertTrue(windowTop >= 0 && windowTop + p.top + p.bottom <= spare)
                assertTrue(windowTop - previousTop in 0..1)
                previousTop = windowTop
            }
        }
    }

    @Test fun avoidsCutoutsAndHidesWhenKeyboardLeavesTooLittleHeight() {
        val bounds = dialogPeekBounds(4, 110, 400, 58, 20, 330, 1f, true)!!
        assertTrue(bounds.right <= 330 && bounds.left >= 58)
        assertNull(dialogPeekBounds(20, 27, 340, 0, 22, 360, 1f, true))
        assertNull(dialogPeekBounds(338, 220, 343, 0, 20, 360, 1f, false))
    }
}

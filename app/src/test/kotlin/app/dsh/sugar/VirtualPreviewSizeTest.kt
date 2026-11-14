package app.dsh.sugar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualPreviewSizeTest {
    @Test fun portraitDefaultUsesMostOfTheAvailableImageArea() {
        val aspect = 9f / 16
        val bounds = virtualPreviewRange(370, 752, 1f, 96, aspect)
        val initial = bounds.at(DEFAULT_PREVIEW_FRACTION)
        val drawnWidth = minOf(initial.width - 4f, initial.height * aspect)

        assertEquals(VirtualPreviewSize(370, 651), bounds.large)
        assertTrue(drawnWidth >= 366 * .90f)
        assertTrue(drawnWidth >= (initial.width - 4) * .92f)
        assertTrue(initial.height >= bounds.large.height * .90f)
        assertTrue(bounds.small.height < initial.height && initial.height < bounds.large.height)
    }

    @Test fun savedAndDefaultZoomLevelsFitTheFrameToTheScreenshot() {
        // Include both width-limited and height-limited screenshots on the same phone.
        for (aspect in listOf(9f / 16, 9f / 20, 16f / 9)) {
            val bounds = virtualPreviewRange(370, 752, 1f, 96, aspect)
            for (fraction in listOf(0f, .22f, DEFAULT_PREVIEW_FRACTION, 1f)) {
                val size = bounds.at(fraction)
                val frameWidth = size.width - 4f
                val drawnWidth = minOf(frameWidth, size.height * aspect)
                val drawnHeight = minOf(size.height.toFloat(), frameWidth / aspect)
                val dimensions = "aspect=$aspect zoom=$fraction size=$size"
                assertTrue(dimensions, drawnWidth / size.width >= .97f)
                assertTrue(dimensions, drawnWidth / frameWidth >= .99f)
                assertTrue(dimensions, drawnHeight / size.height >= .99f)
            }
        }
    }

    @Test fun frameFollowsScreenshotOrientationInALandscapeWindow() {
        val wide = virtualPreviewRange(780, 330, 1f, 96, 16f / 9)
        val tall = virtualPreviewRange(780, 330, 1f, 96, 9f / 16)

        assertEquals(VirtualPreviewSize(420, 234), wide.large)
        assertEquals(VirtualPreviewSize(240, 156), tall.small)
        assertEquals(VirtualPreviewSize(240, 234), tall.large)
        assertTrue(tall.at(DEFAULT_PREVIEW_FRACTION).height > 210)
        val landscapeInPortrait = virtualPreviewRange(370, 752, 1f, 96, 16f / 9)
        assertEquals(VirtualPreviewSize(370, 206), landscapeInPortrait.large)
    }

    @Test fun sideToolbarFitsTallScreenshotsAtEveryZoomInLandscape() {
        val header = 48
        val sideChrome = header + PREVIEW_SIDE_BOTTOM_INSET_DP
        for (aspect in listOf(9f / 16, 9f / 20)) {
            assertTrue(useSidePreviewToolbar(780, 330, 1f, header, aspect))
            val bounds = virtualPreviewRange(780, 330, 1f, sideChrome, aspect, sideToolbar = true)
            assertEquals(272, bounds.large.height)
            assertTrue(bounds.small.width < bounds.large.width && bounds.small.height < bounds.large.height)
            for (zoom in listOf(0f, .22f, DEFAULT_PREVIEW_FRACTION, 1f)) {
                val size = bounds.at(zoom)
                val frameWidth = size.width - 4 - 48
                val drawnHeight = minOf(size.height.toFloat(), frameWidth / aspect)
                assertTrue(drawnHeight >= size.height - 2)
                assertTrue(drawnHeight * aspect >= frameWidth - 1f)
                if (zoom == DEFAULT_PREVIEW_FRACTION) {
                    val fraction = (drawnHeight * aspect / size.width) * (drawnHeight / (size.height + sideChrome))
                    val expectedFill = if (aspect == 9f / 16) .60f else .57f
                    assertTrue("aspect=$aspect fill=$fraction", fraction >= expectedFill)
                }
            }
        }
    }

    @Test fun deviceLandscapeBoundsGiveTheScreenshotMoreThanSixtyPercentOfTheCard() {
        val aspect = 9f / 16
        val header = 122
        val sideChrome = header + 26 // 10dp bottom inset at 2.55x
        val toolbar = 122
        val margins = 10
        assertTrue(useSidePreviewToolbar(2194, 857, 2.55f, header, aspect))
        val side = virtualPreviewRange(2194, 857, 2.55f, sideChrome, aspect, sideToolbar = true)
        val initial = side.at(DEFAULT_PREVIEW_FRACTION)
        val drawnHeight = minOf(initial.height.toFloat(), (initial.width - margins - toolbar) / aspect)
        val fill = (drawnHeight * aspect / initial.width) * (drawnHeight / (initial.height + sideChrome))
        assertEquals(VirtualPreviewSize(531, 709), side.large)
        assertEquals(VirtualPreviewSize(525, 698), initial)
        assertTrue(fill >= .61f)
    }

    @Test fun keepBottomToolbarWhenItBetterFitsAConstrainedWindowOrLargeTablet() {
        assertTrue(!useSidePreviewToolbar(780, 330, 1f, 48, 16f / 9))
        assertTrue(!useSidePreviewToolbar(170, 540, 1f, 48, 9f / 16))
        assertTrue(!useSidePreviewToolbar(48, 330, 1f, 48, 9f / 16))
        assertTrue(!useSidePreviewToolbar(1200, 900, 1f, 48, 9f / 16))
    }

    @Test fun veryShortSplitWindowsUseTheBottomRowWhenFiveActionsFitAcross() {
        assertTrue(!useSidePreviewToolbar(520, 130, 1f, 48, 9f / 16))
        assertTrue(!useSidePreviewToolbar(520, 288, 1f, 48, 9f / 16))
        assertTrue(!useSidePreviewToolbar(240, 130, 1f, 48, 9f / 16))
        assertTrue(useSidePreviewToolbar(239, 130, 1f, 48, 9f / 16))
        assertTrue(useSidePreviewToolbar(520, 330, 1f, 48, 9f / 16))
    }

    @Test fun shortSideToolbarShrinksAndTallSideToolbarAllowsFullSizeTouchTargets() {
        val sideChrome = 48 + PREVIEW_SIDE_BOTTOM_INSET_DP
        val short = virtualPreviewRange(520, 180, 1f, sideChrome, .5f, sideToolbar = true)
        assertEquals(VirtualPreviewSize(113, 122), short.large)
        assertTrue(short.small.height in 1 until short.large.height)
        val tall = virtualPreviewRange(1000, 560, 1f, sideChrome, .5f, sideToolbar = true)
        assertEquals(VirtualPreviewSize(172, 240), tall.small)
    }

    @Test fun narrowAndShortWindowsKeepControlsAndAllowVerticalResizing() {
        val bounds = virtualPreviewRange(164, 178, 1f, 166, 1.65f)
        assertEquals(VirtualPreviewSize(164, 8), bounds.small)
        assertEquals(VirtualPreviewSize(164, 12), bounds.large)
        assertEquals(bounds.small, bounds.at(-2f))
        assertEquals(bounds.large, bounds.at(5f))
        assertEquals(VirtualPreviewSize(164, 10), bounds.at(.5f))
        val collapsed = virtualPreviewRange(2, 20, 2f, 96, .5f)
        assertEquals(VirtualPreviewSize(2, 1), collapsed.small)
        assertEquals(collapsed.small, collapsed.large)
    }

    @Test fun fractionalDensityRoundsEachSideMarginBeforeFittingTheImage() {
        // Each 2dp margin occupies 3px at 1.25x, leaving 445px for the image.
        val bounds = virtualPreviewRange(451, 900, 1.25f, 121, 2f)
        assertEquals(VirtualPreviewSize(451, 223), bounds.large)
        assertEquals(300, bounds.small.width)
        val large = virtualPreviewRange(721, 1400, 2f, 192, 9f / 16)
        assertEquals(VirtualPreviewSize(688, 1208), large.large)
    }

    @Test fun draggingAcrossCenteredWindowTracksFingerAndClamps() {
        val bounds = VirtualPreviewRange(VirtualPreviewSize(260, 100), VirtualPreviewSize(360, 500))
        assertEquals(.7f, bounds.dragged(.2f, 25f, 100f), .0001f)
        assertEquals(.2f, bounds.dragged(.7f, -25f, -100f), .0001f)
        assertTrue(bounds.dragged(.2f, 20f, 0f) > .2f)
        assertEquals(1f, bounds.dragged(.4f, 1000f, 600f), 0f)
        assertEquals(0f, bounds.dragged(.4f, -1000f, -600f), 0f)
    }

    @Test fun draggingStillWorksAtToolbarWidthAndAcrossOnePixelOfTravel() {
        val bounds = VirtualPreviewRange(VirtualPreviewSize(240, 100), VirtualPreviewSize(240, 500))
        assertEquals(.7f, bounds.dragged(.2f, 100f, 0f), .0001f)
        assertEquals(.2f, bounds.dragged(.7f, 0f, -100f), .0001f)
        val tiny = VirtualPreviewRange(VirtualPreviewSize(164, 1), VirtualPreviewSize(164, 2))
        assertEquals(1f, tiny.dragged(0f, 0f, .5f), 0f)
        val fixed = VirtualPreviewRange(bounds.small, bounds.small)
        assertEquals(.4f, fixed.dragged(.4f, 20f, 80f), 0f)
    }
}
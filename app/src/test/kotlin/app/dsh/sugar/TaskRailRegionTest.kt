package app.dsh.sugar

import org.junit.Assert.*
import org.junit.Test

class TaskRailRegionTest {
    @Test fun fullHeightMovingBandUsesWindowCoordinates() {
        val band = TaskRailRegion(340f, 32f, 500f, 2120f)
        assertTrue(band.contains(352f, 100f))
        assertTrue(band.contains(499f, 2119f))
        assertFalse(band.contains(1050f, 100f))
        assertFalse(band.contains(348f, 2122f))
        assertFalse(band.contains(500f, 1050f))
        assertFalse(band.contains(430f, 2120f))
    }

    @Test fun invisibleSideTargetDoesNotExpandThePaintedCapsule() {
        val painted = TaskRailRegion(4f, 740f, 14f, 850f, 5f)
        val touch = TaskRailRegion(0f, 32f, 31f, 1530f)
        assertTrue(touch.contains(8f, 1250f))
        assertFalse(painted.contains(8f, 1250f))
        assertTrue(painted.contains(9f, 741f))
        assertFalse(painted.contains(4f, 740f))
        assertFalse(touch.contains(8f, 1540f))
    }

    @Test fun capsRadiusAndSkipsMalformedGeometry() {
        val badge = TaskRailRegion(1f, 2f, 9f, 10f, 300f)
        assertEquals(4f, badge.corner, 0f)
        assertFalse(badge.contains(1f, 2f))
        assertTrue(badge.contains(5f, 6f))
        assertFalse(badge.copy(right = .5f).contains(4f, 4f))
        assertFalse(badge.copy(left = Float.NaN).contains(4f, 4f))
        assertFalse(badge.copy(top = Float.POSITIVE_INFINITY).contains(4f, 4f))
    }
}

package app.dsh.sugar.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileImageMappingTest {
    @Test fun croppedScaledImageMapsToTheSelectedDisplayRegion() {
        val geometry = MobileImages.Mapping("roi", 100, 40, 120.0, 256.0, 2.0, 2.5)
        assertEquals(120.0 to 256.0, geometry.displayPoint(0.0, 0.0))
        assertEquals(260.0 to 306.0, geometry.displayPoint(70.0, 20.0))
        assertThrows(ScreenProblem::class.java) { geometry.displayPoint(100.0, 0.0) }
        assertThrows(ScreenProblem::class.java) { geometry.displayPoint(99.0, 40.0) }
        assertThrows(ScreenProblem::class.java) { geometry.displayPoint(-1.0, 2.0) }
    }
}

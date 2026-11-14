package app.dsh.sugar

import org.junit.Assert.*
import org.junit.Test

class DialogFrameTouchTest {
    @Test fun onlyATapStartingAndEndingInTheFrameRequestsOutsideTouch() {
        val tap = DialogFrameTap(8)
        assertFalse(tap.down(20f, 30f, false))
        assertFalse(tap.finish(20f, 31f, true))
        assertTrue(tap.down(20f, 30f, true))
        assertFalse(tap.finish(21f, 32f, false))
        assertTrue(tap.down(20f, 30f, true))
        assertTrue(tap.finish(21f, 32f, true))
        assertFalse(tap.tracking)
    }

    @Test fun swipingBackToTheMarginAndAddingPointersDoNotDismiss() {
        val tap = DialogFrameTap(8)
        tap.down(10f, 10f, true)
        tap.move(10f, 100f)
        assertFalse(tap.finish(10f, 10f, true))
        tap.down(10f, 10f, true)
        tap.additionalPointer()
        assertFalse(tap.finish(10f, 10f, true))
        tap.down(10f, 10f, true)
        tap.cancel()
        assertFalse(tap.finish(10f, 10f, true))
        assertTrue(tap.down(10f, 10f, true))
        assertTrue(tap.finish(10f, 11f, true))
    }
}

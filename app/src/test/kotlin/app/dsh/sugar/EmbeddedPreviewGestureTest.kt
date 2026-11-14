package app.dsh.sugar

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedPreviewGestureTest {
    @Test fun tapAndSmallDiagonalMovementWaitForDirection() {
        assertEquals(0, EmbeddedPreviewGesture.intent(0f, 0f, 12f))
        assertEquals(0, EmbeddedPreviewGesture.intent(10f, 9f, 12f))
        assertEquals(0, EmbeddedPreviewGesture.intent(13f, 11f, 12f))
    }

    @Test fun verticalAndOutwardDragsStayWithTheDocument() {
        assertEquals(-1, EmbeddedPreviewGesture.intent(16f, 19f, 12f))
        assertEquals(-1, EmbeddedPreviewGesture.intent(-15f, 2f, 12f))
    }

    @Test fun sufficientlyHorizontalInwardDragCanBeClaimed() {
        assertEquals(1, EmbeddedPreviewGesture.intent(32f, 18f, 12f))
    }
}

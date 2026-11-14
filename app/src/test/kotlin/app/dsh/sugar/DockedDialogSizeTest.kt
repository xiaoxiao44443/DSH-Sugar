package app.dsh.sugar

import org.junit.Assert.assertEquals
import org.junit.Test

class DockedDialogSizeTest {
    @Test fun portraitReservesSpaceOnTheOpenSide() {
        assertEquals(DockedDialogSize(304, -31), dockedDialogSize(390, 0, 0, false, 62, 12, 560))
        assertEquals(DockedDialogSize(304, 31), dockedDialogSize(390, 0, 0, true, 62, 12, 560))
    }

    @Test fun compactChooserCentersInTheAvailableLandscapeSpace() {
        assertEquals(DockedDialogSize(320, 0), dockedDialogSize(844, 36, 12, false, 0, 12, 320))
        assertEquals(DockedDialogSize(320, 31), dockedDialogSize(844, 36, 12, true, 98, 12, 320))
        assertEquals(DockedDialogSize(254, -31), dockedDialogSize(370, 18, 12, false, 74, 12, 320))
    }

    @Test fun landscapeCutoutIsNotReservedTwice() {
        assertEquals(DockedDialogSize(560, 31), dockedDialogSize(844, 36, 12, true, 98, 12, 560))
        assertEquals(DockedDialogSize(560, -31), dockedDialogSize(844, 12, 36, false, 98, 12, 560))
    }
}

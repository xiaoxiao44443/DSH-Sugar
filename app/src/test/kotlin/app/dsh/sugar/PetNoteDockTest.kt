package app.dsh.sugar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetNoteDockTest {
    @Test fun crossingTheMiddleDoesNotFlipAPageThatStillFits() {
        assertTrue(dockNoteOnLeft(true, left = 220, right = 320, preferred = 200, slack = 10))
        assertFalse(dockNoteOnLeft(false, left = 320, right = 220, preferred = 200, slack = 10))
    }

    @Test fun narrowScreensCanReverseWithoutChatteringAtTheCrossover() {
        var left = dockNoteOnLeft(null, 80, 120, 160, 10)
        assertFalse(left)
        for (space in 80..120) left = dockNoteOnLeft(left, space, 200 - space, 160, 10)
        assertTrue(left)
        for (space in listOf(104, 100, 96, 102)) {
            left = dockNoteOnLeft(left, space, 200 - space, 160, 10)
            assertTrue(left)
        }
        assertFalse(dockNoteOnLeft(left, 80, 120, 160, 10))
    }
}

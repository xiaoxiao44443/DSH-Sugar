package app.dsh.sugar.mobile

import android.view.accessibility.AccessibilityEvent.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MainWindowChangesTest {
    private val tracker = MainWindowChanges()
    private val target = listOf(3, 7) // an overlay in front of the game

    @Test fun unrelatedNotificationsDoNotInvalidateMainFrame() {
        val start = tracker.snapshot(target)
        tracker.changed(2, 5, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_ADDED)
        tracker.changed(0, 9, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_BOUNDS)
        tracker.changed(0, 9, TYPE_WINDOW_STATE_CHANGED, 0)
        tracker.changed(0, 7, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED)
        assertEquals(start, tracker.snapshot(target))
    }

    @Test fun targetPaneGeometryAndPossibleOcclusionInvalidate() {
        val changes = listOf(
            Triple(7, TYPE_WINDOW_STATE_CHANGED, 0),
            Triple(7, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_BOUNDS),
            Triple(3, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_BOUNDS),
            Triple(9, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_LAYER),
            Triple(9, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_ADDED),
            Triple(-1, TYPE_WINDOW_STATE_CHANGED, 0),
            Triple(-1, TYPE_WINDOWS_CHANGED, 0),
        )
        for ((window, type, flags) in changes) {
            val start = tracker.snapshot(target)
            tracker.changed(0, window, type, flags)
            assertNotEquals(start, tracker.snapshot(target))
        }
    }

    @Test fun losingFocusAndReturningToTheSameWindowStillInvalidates() {
        val start = tracker.snapshot(target)
        tracker.changed(0, 7, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_ACTIVE)
        tracker.changed(0, 7, TYPE_WINDOWS_CHANGED, WINDOWS_CHANGE_ACTIVE)
        assertNotEquals(start, tracker.snapshot(target))
        val observed = tracker.snapshot(target)
        tracker.snapshot(listOf(3, 11))
        assertNotEquals(observed, tracker.snapshot(target))
    }
}

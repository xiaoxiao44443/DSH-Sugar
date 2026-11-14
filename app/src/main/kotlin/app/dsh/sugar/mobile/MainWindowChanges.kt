package app.dsh.sugar.mobile

import android.view.Display
import android.view.accessibility.AccessibilityEvent

/** Version of the active main window and windows that may cover it, shared by capture and dispatch. */
internal class MainWindowChanges {
    private var windowsThroughActive = emptyList<Int>()
    private var version = 0L

    @Synchronized
    fun snapshot(orderedIds: List<Int>): Long {
        if (orderedIds != windowsThroughActive) {
            windowsThroughActive = orderedIds
            version++
        }
        return version
    }

    @Synchronized
    fun changed(displayId: Int, windowId: Int, type: Int, changes: Int) {
        if (displayId >= 0 && displayId != Display.DEFAULT_DISPLAY) return
        val mayCoverTarget = windowId < 0 || windowId in windowsThroughActive
        val relevant = when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> mayCoverTarget
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // A modal can briefly cover the target and disappear between two snapshots.
                val stackChanges = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
                    AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
                    AccessibilityEvent.WINDOWS_CHANGE_LAYER
                val targetChanges = AccessibilityEvent.WINDOWS_CHANGE_BOUNDS or AccessibilityEvent.WINDOWS_CHANGE_PIP or
                    AccessibilityEvent.WINDOWS_CHANGE_PARENT or AccessibilityEvent.WINDOWS_CHANGE_CHILDREN or
                    AccessibilityEvent.WINDOWS_CHANGE_TITLE
                changes == 0 || changes and stackChanges != 0 || (mayCoverTarget && changes and targetChanges != 0)
            }
            else -> false
        }
        if (relevant) version++
    }
}

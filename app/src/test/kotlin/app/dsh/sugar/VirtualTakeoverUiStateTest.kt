package app.dsh.sugar

import app.dsh.sugar.runtime.MobileUseTasks.VirtualTask
import org.junit.Assert.*
import org.junit.Test

class VirtualTakeoverUiStateTest {
    private val task = VirtualTask("chat", "lease", 12, "active", "project")
    private val target = VirtualPreviewTarget("chat", "lease")

    @Test fun takeoverKeepsTheLeaseButSuspendsPreviewCaptures() {
        for (control in listOf("taking_over", "user", "restoring", "unknown")) {
            val controlled = task.copy(controlState = control)
            assertEquals(controlled, task.currentScreen(listOf(controlled)))
            assertFalse(controlled.previewCaptureAllowed)
        }
        assertTrue(task.previewCaptureAllowed)
        assertTrue(task.copy(phase = "parked").previewCaptureAllowed)
        assertFalse(task.copy(phase = "starting").previewCaptureAllowed)
    }

    @Test fun returnRequestWaitsForOwningConversationAndIsConsumedOnce() {
        val pending = PendingVirtualPreview()
        pending.request(target)
        assertNull(pending.take("different-chat", listOf(task)))
        assertEquals(target, pending.take("chat", listOf(task)))
        assertNull(pending.take("chat", listOf(task)))
    }

    @Test fun requestWaitsUntilConfirmedRestoration() {
        val pending = PendingVirtualPreview()
        pending.request(target)
        for (control in listOf("taking_over", "user", "restoring", "unknown"))
            assertNull(pending.take("chat", listOf(task.copy(controlState = control))))
        assertEquals(target, pending.take("chat", listOf(task.copy(phase = "parked"))))
    }

    @Test fun vanishedLeaseDoesNotRedirectToReplacementOrReplayLater() {
        val pending = PendingVirtualPreview()
        pending.request(target)
        assertNull(pending.take("chat", listOf(task.copy(taskId = "replacement"))))
        assertNull(pending.take("chat", listOf(task)))
    }

    @Test fun alreadyVisibleUiConsumesReturnWithoutWaitingForAnotherTaskEvent() {
        val pending = PendingVirtualPreview()
        var opened: VirtualPreviewTarget? = null
        val listener = { opened = pending.take("chat", listOf(task)) }
        pending.addListener(listener)
        assertTrue(pending.request(target))
        assertEquals(target, opened)
        assertNull(pending.take("chat", listOf(task)))
    }

    @Test fun destroyedUiIsNotNotifiedAndReplacementCanConsumeTheRequest() {
        val pending = PendingVirtualPreview()
        var calls = 0
        val listener = { calls++; Unit }
        pending.addListener(listener)
        pending.removeListener(listener)
        assertFalse(pending.request(target))
        assertEquals(0, calls)
        assertEquals(target, pending.take("chat", listOf(task)))
    }

    @Test fun onlyConfirmedUserControlCanFocusTheApp() {
        for (phase in listOf("active", "parked")) {
            assertTrue(task.copy(phase = phase, controlState = "user").allowsControlAction(VirtualControlAction.FOCUS))
            assertTrue(task.copy(phase = phase, controlState = "unknown").allowsControlAction(VirtualControlAction.RESTORE))
        }
        for (control in listOf("agent", "unknown", "taking_over", "restoring", "focusing", "closing", "close_failed"))
            assertFalse(task.copy(controlState = control).allowsControlAction(VirtualControlAction.FOCUS))
    }

    @Test fun closingKeepsTheLeaseVisibleAndNeverOffersRestoreOrFocus() {
        for (state in listOf("closing", "close_failed")) {
            val closing = task.copy(phase = state, controlState = state)
            assertEquals(closing, task.currentScreen(listOf(closing)))
            assertFalse(closing.previewCaptureAllowed)
            assertFalse(closing.allowsControlAction(VirtualControlAction.FOCUS))
            assertFalse(closing.allowsControlAction(VirtualControlAction.RESTORE))
            assertEquals(state == "close_failed", closing.allowsControlAction(VirtualControlAction.RETRY_CLOSE))
        }
        assertFalse(task.copy(controlState = "close_failed").allowsControlAction(VirtualControlAction.RETRY_CLOSE))
    }

    @Test fun ordinaryClosingNeverCreatesTakeoverControls() {
        for (phase in listOf("active", "parked", "closing", "close_failed", "stopped"))
            assertFalse(task.copy(phase = phase, controlState = "closing").takeoverOverlayVisible)
        for (control in listOf("agent", "user", "restoring", "taking_over", "unknown"))
            assertFalse(task.copy(phase = "closing", controlState = control).takeoverOverlayVisible)
        for (control in listOf("taking_over", "user", "focusing", "restoring", "unknown"))
            assertTrue(task.copy(controlState = control).takeoverOverlayVisible)
        assertTrue(task.copy(phase = "close_failed", controlState = "close_failed").takeoverOverlayVisible)
    }

    @Test fun confirmedForegroundDisablesOnlyFocusAndUnknownDoesNotPretendToBeCurrent() {
        val controlled = task.copy(controlState = "user")
        val current = controlled.copy(controlForeground = true)
        assertTrue(current.controlAppIsForeground)
        assertFalse(current.allowsControlAction(VirtualControlAction.FOCUS))
        assertTrue(current.allowsControlAction(VirtualControlAction.RESTORE))
        for (foreground in listOf(false, null)) {
            val other = controlled.copy(controlForeground = foreground)
            assertFalse(other.controlAppIsForeground)
            assertTrue(other.allowsControlAction(VirtualControlAction.FOCUS))
        }
        assertFalse(current.copy(controlState = "unknown").controlAppIsForeground)
    }

    @Test fun foregroundPollingHasOneInFlightAndStableRefreshDoesNotInvalidateIt() {
        val polling = VirtualForegroundPolling()
        assertTrue(polling.select(target))
        val request = polling.begin()!!
        assertNull(polling.begin())
        assertFalse(polling.select(target))
        assertTrue(polling.finish(request))
        assertNotNull(polling.begin())
    }

    @Test fun hiddenOrReplacedControlDiscardsOldForegroundCompletion() {
        val polling = VirtualForegroundPolling()
        polling.select(target)
        val hidden = polling.begin()!!
        polling.select(null)
        assertFalse(polling.finish(hidden))
        assertNull(polling.begin())
        polling.select(target)
        val previous = polling.begin()!!
        val replacement = VirtualPreviewTarget("other", "new-lease")
        polling.select(replacement)
        assertNull(polling.begin())
        assertFalse(polling.finish(previous))
        assertEquals(replacement, polling.begin()!!.target)
    }

    @Test fun hidingThenShowingSameLeaseDoesNotAcceptOldForegroundQuery() {
        val polling = VirtualForegroundPolling()
        polling.select(target)
        val beforeHide = polling.begin()!!
        polling.select(null)
        polling.select(target)
        assertFalse(polling.finish(beforeHide))
        assertTrue(polling.finish(polling.begin()!!))
    }

    @Test fun newestExplicitReturnWins() {
        val pending = PendingVirtualPreview()
        val other = task.copy(conversation = "other", taskId = "other-lease")
        val otherTarget = VirtualPreviewTarget(other.conversation, other.taskId)
        pending.request(target)
        pending.request(otherTarget)
        assertNull(pending.take("chat", listOf(task, other)))
        assertEquals(otherTarget, pending.take("other", listOf(task, other)))
    }
}

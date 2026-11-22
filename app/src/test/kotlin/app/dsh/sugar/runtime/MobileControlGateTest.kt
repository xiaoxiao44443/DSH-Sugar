package app.dsh.sugar.runtime

import org.junit.Assert.*
import org.junit.Test

class MobileControlGateTest {
    @Test fun returningDoesNotReplayAnActionQueuedBeforeTakeover() {
        val gate = MobileControlGate()
        val old = gate.generation
        assertTrue(gate.allows(old))
        assertTrue(gate.begin(false))
        assertFalse(gate.allows(old))
        assertTrue(gate.complete(gate.generation, "user", null))
        assertTrue(gate.begin(true))
        assertTrue(gate.complete(gate.generation, "agent", null))
        assertFalse(gate.allows(old))
        assertTrue(gate.needsObservation)
        gate.observed(old)
        assertTrue(gate.needsObservation)
        gate.observed(gate.generation)
        assertFalse(gate.needsObservation)
    }

    @Test fun missingReplyRemainsBlockedAndCanBeRestored() {
        val gate = MobileControlGate()
        gate.begin(false)
        gate.complete(gate.generation, null, "Connection timed out")
        assertEquals("unknown", gate.state)
        assertFalse(gate.allows(gate.generation))
        assertFalse(gate.begin(false))
        assertTrue(gate.begin(true))
    }

    @Test fun aLateReplyCannotReopenAClosedLease() {
        val gate = MobileControlGate()
        gate.begin(false)
        val request = gate.generation
        gate.close()
        assertFalse(gate.complete(request, "user", null))
        assertEquals("closed", gate.state)
    }

    @Test fun transitionsCannotOverlapAndARejectedTakeoverStillRequiresFreshObservation() {
        val gate = MobileControlGate()
        assertTrue(gate.begin(false))
        assertFalse(gate.begin(false))
        assertFalse(gate.begin(true))
        gate.complete(gate.generation, "agent", "Unsupported")
        assertTrue(gate.needsObservation)
        assertEquals("Unsupported", gate.error)
        assertTrue(gate.begin(false))
        assertNull(gate.error)
    }

    @Test fun bringingBackAnAppKeepsTheAgentPausedAndDoesNotOverlapReturn() {
        val gate = MobileControlGate()
        assertFalse(gate.beginFocus())
        gate.begin(false)
        gate.complete(gate.generation, "user", null)
        val beforeFocus = gate.generation
        assertTrue(gate.beginFocus())
        assertFalse(gate.begin(true))
        assertFalse(gate.beginFocus())
        assertFalse(gate.allows(beforeFocus))
        assertTrue(gate.complete(gate.generation, "user", null))
        assertEquals("user", gate.state)
        assertFalse(gate.allows(gate.generation))
        assertTrue(gate.begin(true))
    }

    @Test fun failedCloseRetainsABlockedLeaseAndOnlyAllowsRetryingClose() {
        val gate = MobileControlGate()
        val old = gate.generation
        assertTrue(gate.beginClose())
        val closing = gate.generation
        assertFalse(gate.beginClose())
        assertFalse(gate.allows(old))
        assertFalse(gate.begin(true))
        assertFalse(gate.complete(closing, "agent", null))
        assertTrue(gate.closeFailed(closing, "Return failed"))
        assertEquals("close_failed", gate.state)
        assertFalse(gate.begin(true))
        assertFalse(gate.beginFocus())
        assertTrue(gate.beginClose())
        assertFalse(gate.closeFailed(closing, "Late failure"))
        gate.close()
        assertFalse(gate.beginClose())
    }

    @Test fun closeOverridesPendingFocusAndRejectsItsLateReplyAndPackage() {
        val gate = MobileControlGate()
        gate.begin(false)
        gate.complete(gate.generation, "user", null)
        gate.recordPackage(gate.generation, "test.original")
        gate.beginFocus()
        val focus = gate.generation
        assertTrue(gate.beginClose())
        assertFalse(gate.complete(focus, "user", null))
        gate.recordPackage(focus, "test.stale")
        assertEquals("test.original", gate.packageName)
        assertEquals("closing", gate.state)
    }


    @Test fun anUnverifiedBorrowKeepsInputBlockedUntilExplicitRecovery() {
        val gate = MobileControlGate()
        val launch = gate.generation
        assertTrue(gate.uncertainDispatch(launch, "Could not verify task location"))
        assertFalse(gate.allows(launch))
        assertEquals("unknown", gate.state)
        assertFalse(gate.beginFocus())
        assertTrue(gate.begin(true))
        val restoring = gate.generation
        assertFalse(gate.uncertainDispatch(launch, "Late launch reply"))
        assertTrue(gate.complete(restoring, "agent", null))
        assertTrue(gate.needsObservation)
    }


    @Test fun foregroundQueriesOnlyAffectPresentationAndExpire() {
        val gate = MobileControlGate()
        gate.begin(false)
        gate.complete(gate.generation, "user", null)
        val version = gate.generation
        assertTrue(gate.recordForeground(version, true, 100L))
        assertEquals(true, gate.foregroundAt(100L))
        assertEquals(true, gate.foregroundAt(3099L))
        assertNull(gate.foregroundAt(3101L))
        assertEquals(version, gate.generation)
        assertEquals("user", gate.state)
        assertFalse(gate.allows(version))
    }

    @Test fun aLateForegroundQueryCannotFollowReturnOrAnotherTakeover() {
        val gate = MobileControlGate()
        gate.begin(false)
        gate.complete(gate.generation, "user", null)
        val old = gate.generation
        gate.recordForeground(old, true, 100L)
        gate.begin(true)
        assertNull(gate.foregroundAt(101L))
        assertFalse(gate.recordForeground(old, true, 102L))
        gate.complete(gate.generation, "agent", null)
        gate.begin(false)
        gate.complete(gate.generation, "user", null)
        assertFalse(gate.recordForeground(old, true, 103L))
        assertNull(gate.foregroundAt(103L))
    }

    @Test fun unknownForegroundClearsDisabledStateWithoutChangingControl() {
        val gate = MobileControlGate()
        gate.begin(false)
        gate.complete(gate.generation, "user", null)
        val version = gate.generation
        gate.recordForeground(version, true, 100L)
        assertTrue(gate.recordForeground(version, null, 200L))
        assertNull(gate.foregroundAt(200L))
        assertEquals("user", gate.state)
        assertFalse(gate.recordForeground(version, null, 300L))
        assertTrue(gate.recordForeground(version, false, 400L))
        assertEquals(false, gate.foregroundAt(400L))
    }

    @Test fun foregroundCannotSurviveClosingOrReviveItsOverlay() {
        val gate = MobileControlGate()
        gate.begin(false)
        gate.complete(gate.generation, "user", null)
        val version = gate.generation
        gate.recordForeground(version, true, 100L)
        gate.beginClose()
        assertFalse(gate.recordForeground(version, false, 200L))
        assertNull(gate.foregroundAt(200L))
        gate.close()
        assertFalse(gate.recordForeground(version, true, 300L))
        assertEquals("closed", gate.state)
    }

}

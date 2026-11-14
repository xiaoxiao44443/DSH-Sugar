package app.dsh.sugar.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileScriptPhaseTest {
    @Test fun sdkOpenPreservesTheDriverReasonAfterASelectedScreenDisconnects() {
        val issue = mobileScriptPhaseError("stopped", "screen_disconnected", "The main screen accessibility connection was lost.")
        assertEquals("screen_disconnected", issue?.code)
        assertEquals("The main screen accessibility connection was lost.", issue?.message)
        assertEquals("unsupported", mobileScriptPhaseError("failed", "unsupported", "Unavailable on this device.")?.code)
    }

    @Test fun endedTasksKeepTheirTerminalStateAndStartingCanStillBePolled() {
        assertEquals("stopped", mobileScriptPhaseError("stopped", null, null)?.code)
        assertEquals("completed", mobileScriptPhaseError("completed", null, null)?.code)
        assertEquals("completed", mobileScriptPhaseError("parked", null, null)?.code)
        assertEquals("starting", mobileScriptPhaseError("starting", null, null)?.code)
        assertNull(mobileScriptPhaseError("active", null, null))
    }
}

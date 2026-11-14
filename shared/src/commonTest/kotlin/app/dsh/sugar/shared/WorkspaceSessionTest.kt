package app.dsh.sugar.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceSessionTest {
    @Test fun retryIgnoresOldWorkerAndBrowserCallbacks() {
        val session = WorkspaceSession()
        val first = session.begin()
        val second = session.begin()
        assertNull(session.connected(first, "http://127.0.0.1:4111/"))
        val endpoint = assertNotNull(session.connected(second, "http://127.0.0.1:4222/"))
        session.failed(first, "stale")
        session.pageVisible(second, "http://127.0.0.1:4111/")
        assertIs<LaunchState.Opening>(session.state)
        session.pageVisible(second, endpoint.home)
        assertEquals(endpoint, assertIs<LaunchState.Ready>(session.state).endpoint)
    }

    @Test fun onlyTheAnnouncedLocalOriginCanOpenInTheBrowser() {
        val endpoint = assertNotNull(WorkspaceEndpoint.parse("http://127.0.0.1:3050"))
        assertTrue(endpoint.owns(endpoint.home + "settings?tab=model"))
        for (other in listOf("http://127.0.0.1:30501/", "http://127.0.0.1:3050@elsewhere/",
            "http://127.0.0.1:3050\\@elsewhere/", "file:///data/private")) assertFalse(endpoint.owns(other))
        for (invalid in listOf("http://127.0.0.1:0/", "http://127.0.0.1:99999/",
            "https://example.org/", endpoint.home + "?token=private")) assertNull(WorkspaceEndpoint.parse(invalid))
    }
}

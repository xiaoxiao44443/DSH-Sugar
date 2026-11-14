package app.dsh.sugar.mobile

import java.io.IOException
import java.util.concurrent.ExecutionException
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayRepliesTest {
    @Test fun interleavedFramesAndCloseResponsesReturnToTheirOwnRequest() {
        val link = VirtualDisplayReplies<String>()
        val previewA = link.begin()
        val observeB = link.begin()
        val stopA = link.begin()
        link.complete(observeB.id, "display 8 tree")
        assertFalse(previewA.result.isDone)
        link.complete(stopA.id, "display 7 stopped")
        link.complete(previewA.id, "display 7 frame")
        assertEquals("display 8 tree",observeB.result.get())
        assertEquals("display 7 stopped",stopA.result.get())
        assertEquals("display 7 frame",previewA.result.get())
    }

    @Test fun abandonedPreviewAndLateReplyDoNotCancelAgentObservation() {
        val link = VirtualDisplayReplies<String>()
        val preview = link.begin()
        val observation = link.begin()
        preview.result.cancel(false)
        link.complete(preview.id,"late frame")
        assertFalse(observation.result.isDone)
        link.complete(observation.id,"observation")
        assertEquals("observation", observation.result.get())
    }

    @Test fun reconnectAndDisconnectSettleOnlyTheirOwnRequests() {
        val old = VirtualDisplayReplies<String>()
        val next = VirtualDisplayReplies<String>()
        val oldA = old.begin()
        val oldB = old.begin()
        val newA = next.begin()
        assertEquals(oldA.id, newA.id)
        val reason = IOException("wireless disconnected")
        old.close(reason)
        old.complete(newA.id,"old screen")
        old.close(IOException("late disconnect"))
        for (ticket in listOf(oldA, oldB))
            assertSame(reason, assertThrows(ExecutionException::class.java) { ticket.result.get() }.cause)
        assertThrows(IOException::class.java) { old.begin() }
        assertFalse(newA.result.isDone)
        next.complete(newA.id,"new screen")
        assertEquals("new screen",newA.result.get())
    }
}

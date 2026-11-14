package app.dsh.sugar

import app.dsh.sugar.runtime.MobileUseTasks.VirtualTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VirtualScreenConversationTest {
    private val a = VirtualTask("chat-a","task-a",12,"active","project")
    private val b = VirtualTask("chat-b","task-b",14,"active","project")

    @Test fun selectedLeaseOwnsTheDestination() {
        val live = listOf(a,b)
        assertEquals("chat-b", b.conversationToOpen(null,live))
        assertEquals("chat-b", b.conversationToOpen("chat-a",live))
        assertNull(a.conversationToOpen("chat-a",live))
    }

    @Test fun transitionBetweenActiveAndParkedKeepsTheSelectedPreview() {
        val parked = a.copy(phase = "parked")
        assertEquals(parked, a.currentScreen(listOf(parked, b)))
        assertEquals(a, parked.currentScreen(listOf(a, b)))
        assertEquals(a.conversation, parked.conversationToOpen(null, listOf(a, b)))
        assertNull(parked.currentScreen(listOf(parked.copy(project = "another"))))
        assertNull(a.currentScreen(listOf(a.copy(phase = "starting"))))
    }

    @Test fun aReplacedOrClosedScreenCannotOpenAnyChat() {
        assertNull(b.conversationToOpen(null,listOf(a)))
        assertNull(b.conversationToOpen(null,listOf(b.copy(taskId = "replacement"))))
        assertNull(b.conversationToOpen(null,listOf(b.copy(conversation = "chat-a"))))
        assertNull(b.conversationToOpen(null,listOf(b.copy(displayId = 15))))
    }
}

package app.dsh.sugar

import org.junit.Assert.*
import org.junit.Test

class PetCaptionPresentationTest {
    private val reply = DesktopPetActivity.Result("chat", "say-1", "reply", "正在跑第 12/24 步～", 1_000_000L)
    private val spoken = DesktopPetActivity.Display("working", "chat", "1", reply, null, "working",
        contentAtMicros = reply.atMicros, turnStartedAt = 10L)
    private val next = spoken.copy(result = null, caption = "保存生成的图片", phase = "2", contentAtMicros = 2_000_000L)

    @Test fun readingEndsWithoutDismissingReplyAndPollsNeverRestartIt() {
        val reading = PetReplyReading()
        assertEquals(4_000L, reading.start(spoken, 4_000, 100))
        assertEquals(3_000L, reading.start(spoken.copy(phase = "poll"), 4_000, 1_100))
        assertEquals(spoken, reading.select(spoken, 4_100))
        assertNull(reading.start(spoken, 4_000, 4_100))
        assertEquals(next, reading.select(next, 4_101))
    }

    @Test fun firstPassDefersOnlyOrdinaryCaptionsAndThenShowsTheLatest() {
        val reading = PetReplyReading()
        reading.start(spoken, 4_000, 100)
        assertEquals(reply, reading.select(next, 200).result)
        val latest = next.copy(caption = "核对图片文件", contentAtMicros = 3_000_000L)
        assertEquals(reply, reading.select(latest, 500).result)
        assertEquals(latest, reading.select(latest, 4_100))
    }

    @Test fun urgentEventsAndScopeChangesAlwaysInterruptReading() {
        for (incoming in listOf(
            next.copy(pose = "asking"), next.copy(pose = "idle"), next.copy(phoneStep = "error"),
            next.copy(result = reply.copy(id = "tool-2", kind = "error")),
            next.copy(result = reply.copy(id = "turn-2", kind = "answer")),
            next.copy(result = reply.copy(id = "say-2", atMicros = 3_000_000L)),
            next.copy(sessionId = "other"), next.copy(turnStartedAt = 20L),
            next.copy(caption = null))) {
            val reading = PetReplyReading()
            reading.start(spoken, 4_000, 100)
            assertEquals(incoming, reading.select(incoming, 200))
        }
    }

    @Test fun semanticAgeUsesOriginalTimestampAndDoesNotResetOnPollOrRedisplay() {
        assertEquals(PetCaptionAge.Tick(null, 30_000), PetCaptionAge.update(spoken, 1_000_000L))
        assertEquals(PetCaptionAge.Tick(30, 5_000), PetCaptionAge.update(spoken.copy(phase = "poll"), 31_000_000L))
        assertEquals(PetCaptionAge.Tick(31, 4_000), PetCaptionAge.update(spoken, 32_000_000L))
        assertEquals(PetCaptionAge.Tick(null, 500), PetCaptionAge.update(next, 31_500_000L))
        assertNull(PetCaptionAge.update(spoken.copy(pose = "phone", result = reply.copy(id = "turn-3", kind = "answer")), 61_000_000L))
        assertNull(PetCaptionAge.update(spoken.copy(pose = "idle"), 61_000_000L))
        assertNull(PetCaptionAge.update(spoken.copy(phoneStep = "awaitingCompanion"), 61_000_000L))
        assertNull(PetCaptionAge.update(next.copy(contentAtMicros = 0L), 61_000_000L))
    }
}

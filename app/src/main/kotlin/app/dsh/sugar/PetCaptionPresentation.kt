package app.dsh.sugar

/** Finish reading before an ordinary tool caption; only the latest pending display is sampled. */
internal class PetReplyReading {
    private var reading: DesktopPetActivity.Display? = null
    private var lastRead: Pair<String, String>? = null
    private var until = 0L

    fun select(incoming: DesktopPetActivity.Display, now: Long): DesktopPetActivity.Display {
        val held = reading ?: return incoming
        val sameScope = held.sessionId == incoming.sessionId && held.turnStartedAt == incoming.turnStartedAt &&
            held.phoneTask?.id == incoming.phoneTask?.id
        val ordinaryCaption = incoming.result == null && !incoming.caption.isNullOrBlank()
        val sameReply = incoming.result == held.result
        if (now >= until || !sameScope || incoming.pose in setOf("idle", "asking") ||
            incoming.phoneStep == "error" || (!ordinaryCaption && !sameReply)) {
            reading = null
            return incoming
        }
        return if (sameReply) incoming else incoming.copy(result = held.result, caption = null,
            phoneStep = null, contentAtMicros = held.contentAtMicros)
    }

    fun start(display: DesktopPetActivity.Display, durationMs: Long, now: Long): Long? {
        val reply = display.result?.takeIf { it.kind == "reply" } ?: return null
        val key = reply.sessionId to reply.id
        if (key != lastRead) {
            lastRead = key
            reading = display
            until = now + durationMs.coerceAtLeast(4_000L)
        }
        return if (reading != null && until > now) until - now else null
    }

    fun clear() { reading = null; lastRead = null; until = 0L }
}

/** Age of the last semantic event, not a claim that an operation is still running.
 * Event timestamps and nowMicros both use CLOCK_MONOTONIC (System.nanoTime). */
internal object PetCaptionAge {
    data class Tick(val seconds: Long?, val nextIn: Long)
    fun update(display: DesktopPetActivity.Display, nowMicros: Long): Tick? {
        if (display.pose in setOf("idle", "asking") || display.phoneStep in setOf("error", "awaitingCompanion") ||
            display.result?.kind in setOf("error", "answer") || display.contentAtMicros <= 0L ||
            (display.result?.text ?: display.caption).isNullOrBlank()) return null
        val elapsed = ((nowMicros - display.contentAtMicros) / 1000L).coerceAtLeast(0L)
        return if (elapsed < 30_000L) Tick(null, 30_000L - elapsed)
            else Tick(elapsed / 1000L, 5_000L - elapsed % 5_000L)
    }
}

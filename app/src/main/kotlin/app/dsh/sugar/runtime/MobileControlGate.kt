package app.dsh.sugar.runtime

/** A generation belongs to one virtual display lease, including its parked turns. */
internal class MobileControlGate {
    @Volatile var state = "agent"
        private set
    @Volatile var generation = 0L
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var packageName: String? = null
        private set
    @Volatile var needsObservation = false
        private set

    private data class Foreground(val generation: Long, val at: Long, val value: Boolean?)
    @Volatile private var foreground: Foreground? = null

    /** Foreground is presentation data, never a change to the control owner. */
    fun foregroundAt(now: Long): Boolean? {
        val observed = foreground ?: return null
        return observed.value.takeIf { state == "user" && observed.generation == generation &&
            now >= observed.at && now - observed.at <= 3000L }
    }

    @Synchronized fun recordForeground(version: Long, value: Boolean?, now: Long): Boolean {
        if (state != "user" || generation != version) return false
        val previous = foregroundAt(now)
        foreground = Foreground(version, now, value)
        return previous != value
    }

    fun allows(version: Long) = state == "agent" && generation == version

    @Synchronized fun begin(restore: Boolean): Boolean {
        if (if (restore) state !in setOf("user", "unknown") else state != "agent") return false
        generation++
        needsObservation = true
        error = null
        state = if (restore) "restoring" else "taking_over"
        return true
    }

    @Synchronized fun complete(version: Long, reported: String?, message: String?): Boolean {
        if (generation != version || state !in setOf("taking_over", "restoring", "focusing")) return false
        error = message
        state = reported?.takeIf { it in setOf("agent", "user", "unknown") } ?: "unknown"
        return true
    }

    @Synchronized fun uncertainDispatch(version: Long, message: String?): Boolean {
        if (!allows(version)) return false
        generation++
        needsObservation = true
        error = message
        state = "unknown"
        return true
    }

    @Synchronized fun beginFocus(): Boolean {
        if (state != "user") return false
        generation++
        needsObservation = true
        error = null
        state = "focusing"
        return true
    }

    @Synchronized fun recordPackage(version: Long, value: String?) {
        if (generation == version && state != "closed" && !value.isNullOrBlank()) packageName = value
    }

    /** Revokes all operations immediately; the display remains alive until native cleanup is verified. */
    @Synchronized fun beginClose(): Boolean {
        if (state in setOf("closed", "closing")) return false
        generation++
        needsObservation = true
        error = null
        state = "closing"
        return true
    }

    @Synchronized fun closeFailed(version: Long, message: String?): Boolean {
        if (generation != version || state != "closing") return false
        state = "close_failed"
        error = message
        return true
    }

    @Synchronized fun observed(version: Long) {
        if (allows(version)) needsObservation = false
    }

    @Synchronized fun close() {
        generation++
        state = "closed"
    }
}

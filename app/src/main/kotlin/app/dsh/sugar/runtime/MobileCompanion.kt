package app.dsh.sugar.runtime

import android.os.SystemClock
import app.dsh.sugar.mobile.MobileFrameHistory
import app.dsh.sugar.mobile.ScreenProblem
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owned by one selected screen for one turn, including when scripts detach and reattach. */
internal class MobileCompanion {
    private val lock = Object()
    private val history = MobileFrameHistory()
    @Volatile private var generation = 0L
    private var sampler: ScheduledFuture<*>? = null
    private var intervalMs = 0L
    private var error: String? = null
    private var pendingUser = false
    private var closed = false

    fun wake() = synchronized(lock) {
        if (!closed) {
            pendingUser = true
            lock.notifyAll()
        }
    }

    fun await(ms: Long, allowed: () -> Boolean): JSONObject = synchronized(lock) {
        val deadline = SystemClock.elapsedRealtime() + ms.coerceIn(0L, 15000L)
        while (!closed && allowed() && !pendingUser) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            lock.wait(left.coerceAtMost(250L))
        }
        if (closed || !allowed()) throw ScreenProblem("session_closed", "This phone task has ended.")
        val reason = if (pendingUser) "user" else "timer"
        pendingUser = false
        JSONObject().put("ok", true).put("reason", reason)
    }

    fun watch(ms: Long, capture: (() -> Boolean) -> JSONObject): JSONObject = synchronized(lock) {
        if (closed) throw ScreenProblem("session_closed", "This phone task has ended.")
        if (ms != 0L && ms !in 1000L..60000L)
            throw ScreenProblem("invalid_argument", "Sampling interval must be 1–60 seconds.")
        if (ms != intervalMs) {
            val version = ++generation
            sampler?.cancel(false)
            sampler = null
            intervalMs = ms
            error = null
            if (ms > 0) {
                val current = { generation == version }
                sampler = executor.scheduleWithFixedDelay({
                    try {
                        val frame = capture(current)
                        val problem = frame.optJSONObject("error")?.optString("code")
                        if (current() && frame.optBoolean("ok") && frame.has("image")) history.add(frame, current)
                        synchronized(lock) { if (generation == version) error = problem }
                    } catch (e: Exception) {
                        synchronized(lock) { if (generation == version) error = e.message ?: "capture_failed" }
                    }
                }, 0L, ms, TimeUnit.MILLISECONDS)
            }
        }
        JSONObject().put("ok", true).put("interval_ms", ms)
    }

    fun read(args: JSONObject): JSONObject {
        val result = history.read(args)
        synchronized(lock) {
            if (closed) throw ScreenProblem("session_closed", "This phone task has ended.")
            return result.put("interval_ms", intervalMs).putOpt("last_capture_error", error)
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            generation++
            sampler?.cancel(false)
            sampler = null
            pendingUser = false
            lock.notifyAll()
        }
        history.close()
    }

    companion object {
        private val executor = ScheduledThreadPoolExecutor(2) {
            Thread(it, "sugar-screen-history").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

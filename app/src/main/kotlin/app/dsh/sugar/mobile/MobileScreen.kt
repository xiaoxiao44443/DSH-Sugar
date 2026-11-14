package app.dsh.sugar.mobile

import android.os.Looper
import android.os.SystemClock
import org.json.JSONException
import org.json.JSONObject

internal class ScreenProblem(val code: String, message: String) : Exception(message)

internal fun screenError(code: String, message: String): JSONObject =
    JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message))

/** A lease owns the main display until the task closes it or the service disconnects. */
internal object MobileScreen {
    private class Session(val id: String, val driver: MobileAccessibilityService) {
        var observation: MainObservation? = null
    }

    private val gate = Any()
    private var service: MobileAccessibilityService? = null
    private var active: Session? = null

    fun available(): Boolean = synchronized(gate) { service != null }

    internal fun connected(driver: MobileAccessibilityService) = synchronized(gate) {
        if (service !== driver) active = null
        service = driver
    }

    internal fun disconnected(driver: MobileAccessibilityService) = synchronized(gate) {
        if (service === driver) {
            active = null
            service = null
        }
    }

    fun open(sessionId: String): JSONObject = synchronized(gate) {
        if (sessionId.isBlank()) return screenError("invalid_session", "A session id is required.")
        val driver = service ?: return screenError("accessibility_unavailable", "Enable Sugar in Accessibility settings.")
        val previous = active
        if (previous != null && sessionId != previous.id)
            return screenError("screen_busy", "Another session is using the main display.")
        if (previous == null) active = Session(sessionId, driver)
        JSONObject().put("ok", true).put("display", "main").put("driver", "accessibility")
            .put("capabilities", JSONObject().put("tree", true).put("image", driver.supportsScreenshot())
                .put("node_action", true).put("gesture", true).put("drag", true).put("text", true)
                .put("launch", true).put("open_app", true).put("recents", true))
    }

    fun isOpen(sessionId: String): Boolean = synchronized(gate) { active?.id == sessionId }

    fun close(sessionId: String) = synchronized(gate) {
        if (active?.id == sessionId) active = null
    }

    fun preview(sessionId: String, allowed: () -> Boolean): JSONObject =
        execute(sessionId, "preview", JSONObject().put("image", true).put("tree", false), allowed)

    /** Run from the bridge's worker thread; Android delivers screenshot and gesture callbacks on main. */
    fun execute(sessionId: String, op: String, args: JSONObject, allowed: () -> Boolean = { true }): JSONObject {
        if (Looper.myLooper() === Looper.getMainLooper())
            return screenError("wrong_thread", "Phone operations require a background worker.")
        val session = synchronized(gate) { active?.takeIf { it.id == sessionId } }
            ?: return screenError("session_closed", "This phone control session is no longer active.")
        return try {
            synchronized(session) {
                checked(session, allowed) {}
                if (op == "observe" || op == "preview") {
                    val preview = op == "preview"
                    fun capture() = session.driver.observe(args, preview) { checked(session, allowed) {} }
                    val observation = try {
                        capture()
                    } catch (error: ScreenProblem) {
                        // Android shares the screenshot interval between history and interactive reads.
                        // Post-gesture feedback has its own retry and original completion timestamp.
                        if (preview || error.code != "screenshot_rate_limited" ||
                            args.has("feedback_after_uptime_ms")) throw error
                        Thread.sleep(360)
                        capture()
                    }
                    checked(session, allowed) {
                        if (preview) observation.json.remove("observation_id")
                        else session.observation = observation
                    }
                    observation.json
                } else {
                    val previous = session.observation
                    if (op !in setOf("tap", "long_press", "input", "swipe", "drag", "home", "back", "launch", "open_app", "recents"))
                        throw ScreenProblem("unsupported", "Unsupported main screen operation.")
                    val requiresObservation = op !in setOf("back", "home", "launch", "open_app", "recents") || args.has("observation_id")
                    if (requiresObservation && (previous == null || previous.id != args.optString("observation_id") ||
                            SystemClock.elapsedRealtime() - previous.time > 60_000)) {
                        throw ScreenProblem("stale_observation", "Read the current display before acting.")
                    }
                    val activeSession = { synchronized(gate) { active === session && allowed() } }
                    session.driver.act(op, args, if (requiresObservation) previous else null, activeSession) { dispatch ->
                        checked(session, allowed) {
                            val sent = dispatch()
                            if (sent) session.observation = null
                            sent
                        }
                    }
                }
            }
        } catch (error: ScreenProblem) {
            screenError(error.code, error.message.orEmpty())
        } catch (error: JSONException) {
            screenError("invalid_argument", error.message ?: "Invalid phone operation arguments.")
        } catch (error: SecurityException) {
            screenError("permission_denied", "Android refused this operation.")
        } catch (error: Exception) {
            screenError("driver_error", error.message ?: "The accessibility driver failed.")
        }
    }

    private fun <T> checked(session: Session, allowed: () -> Boolean, action: () -> T): T = synchronized(gate) {
        if (active !== session || service !== session.driver || !allowed())
            throw ScreenProblem("session_closed", "This phone control session has stopped.")
        action()
    }
}

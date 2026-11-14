package app.dsh.sugar

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import app.dsh.sugar.shared.WorkspaceEndpoint
import org.json.JSONObject
import kotlin.math.abs

/** The renderer cannot bubble touch events from a sandboxed document preview into the shell. */
@SuppressLint("ClickableViewAccessibility") // All taps retain WebView's original down/up pair.
internal class EmbeddedPreviewGesture(
    private val view: WebView,
    private val endpoint: WorkspaceEndpoint,
    private val previewOpen: () -> Boolean
) {
    private class Touch(val id: Int, val width: Int, val height: Int, val startX: Float, val startY: Float) {
        var elapsedMs = 0L
        var x = startX
        var y = startY
        var cssPerPixel = 0f
        var claimed = false
    }

    private var tracking: Touch? = null
    private var sequence = 0
    private var consumeRemainder = false
    private var queued = false
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val trusted = "window === top && location.origin === " + JSONObject.quote(endpoint.origin) +
        " && location.pathname === '/'"
    private val moveFrame = Runnable {
        queued = false
        tracking?.takeIf { it.claimed }?.let { send(it, "move") }
    }

    private fun live(): Boolean = view.isAttachedToWindow && view.hasWindowFocus() && view.isShown &&
        (view.url == endpoint.home || view.url == endpoint.origin)

    fun attach() { view.setOnTouchListener { _, e -> handle(e) } }

    private fun probe(event: MotionEvent) {
        if (!previewOpen() || !live() || event.pointerCount != 1 ||
            event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
        val x = event.x
        val y = event.y
        val w = view.width
        val h = view.height
        // The JS hit test makes the final decision in CSS pixels. This merely avoids bridge
        // traffic for touches in the middle of an open inspector, including normal iframe taps.
        val edge = 38 * view.resources.displayMetrics.density
        if (w <= 0 || h <= 0 || x !in 0f..w.toFloat() || y !in 0f..h.toFloat() || x > edge && x < w - edge) return
        sequence = if (sequence == Int.MAX_VALUE) 1 else sequence + 1
        val touch = Touch(sequence, w, h, x, y)
        tracking = touch
        val js = """(() => { if (!($trusted)) return 0;
            const w = window.visualViewport?.width || window.innerWidth;
            if (Math.abs((window.visualViewport?.scale || 1) - 1) > 0.01) return 0;
            return window.SugarEdgeNav?.isEmbeddedEdge($x*w/$w,$y*w/$w) ? w : 0; })()"""
        view.evaluateJavascript(js) { result ->
            if (tracking !== touch) return@evaluateJavascript
            val cssWidth = result?.toFloatOrNull()
            if (cssWidth == null || !cssWidth.isFinite() || cssWidth <= 0 ||
                !previewOpen() || !live() || view.width != w || view.height != h) tracking = null
            else touch.cssPerPixel = cssWidth / w
        }
    }

    private fun handle(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            cancel()
            consumeRemainder = false
            probe(event)
            return false
        }
        if (consumeRemainder) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) consumeRemainder = false
            return true
        }
        val touch = tracking ?: return false
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN ||
            event.pointerCount != 1 || !live() || view.width != touch.width || view.height != touch.height) {
            cancel()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) consumeRemainder = false
            return touch.claimed
        }
        if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return touch.claimed
        touch.elapsedMs = event.eventTime - event.downTime
        touch.x = event.x
        touch.y = event.y
        if (!touch.claimed) {
            val dx = (touch.x - touch.startX) * if (touch.startX < touch.width / 2f) 1 else -1
            val dy = abs(touch.y - touch.startY)
            val intent = intent(dx, dy, slop)
            if (intent < 0 || !previewOpen()) { tracking = null; return false }
            if (intent == 0 || touch.cssPerPixel == 0f) {
                if (action == MotionEvent.ACTION_UP) tracking = null
                return false
            }
            // Cancel just the WebView's existing touch stream. Activity still receives its
            // original MotionEvent for Android's predictive back and system gesture handling.
            val cancel = MotionEvent.obtain(event)
            try {
                cancel.action = MotionEvent.ACTION_CANCEL
                view.onTouchEvent(cancel)
            } finally { cancel.recycle() }
            touch.claimed = true
            send(touch, "start")
        }
        if (action == MotionEvent.ACTION_UP) {
            clearFrame()
            send(touch, "end")
            tracking = null
        } else if (!queued) {
            queued = true
            view.postOnAnimation(moveFrame)
        }
        return true
    }

    private fun send(t: Touch, phase: String) {
        // Cancelling an owned gesture on focus loss still tears down the JS drag in this
        // document. Nothing is evaluated in a replacement, external or detached WebView.
        if (!view.isAttachedToWindow || view.url !in listOf(endpoint.home, endpoint.origin) ||
            phase != "cancel" && !live()) return
        val f = t.cssPerPixel
        view.evaluateJavascript("if ($trusted) window.SugarEdgeNav?.embeddedDrag(" +
            "'$phase',${t.x * f},${t.y * f},${t.startX * f},${t.startY * f},${t.id},${t.elapsedMs})", null)
    }

    private fun clearFrame() {
        view.removeCallbacks(moveFrame)
        queued = false
    }

    fun cancel(notifyPage: Boolean = true) {
        clearFrame()
        val touch = tracking
        tracking = null
        if (touch?.claimed == true) {
            consumeRemainder = true
            if (notifyPage) send(touch, "cancel")
        }
    }

    fun detach() { cancel(notifyPage = false); view.setOnTouchListener(null) }

    internal companion object {
        // Negative is a permanent yield to the document, zero waits for clearer intent.
        fun intent(inward: Float, dy: Float, slop: Float): Int = when {
            inward < -slop || dy > slop && dy >= abs(inward) -> -1
            inward >= slop && inward >= dy * 1.4f -> 1
            else -> 0
        }
    }
}

package app.dsh.sugar

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout

/** Aligns the compositor's blur surface with the native panel, without capturing screen images. */
internal class MobileGlassBackdrop(container: FrameLayout, private val panel: View) {
    private val page = (0 until container.childCount).map(container::getChildAt).filterIsInstance<WebView>().firstOrNull()
    private var radius = 0
    private var blur = 0
    private var dark = false
    private var queued = false
    private var last = ""
    private val glassLocation = IntArray(2)
    private val pageLocation = IntArray(2)
    private val draw = Runnable {
        queued = false
        val web = page ?: return@Runnable
        if (!panel.isShown || panel.width <= 0 || panel.height <= 0 || web.width <= 0) {
            clear()
            return@Runnable
        }
        // One width unit on each axis avoids stretching the radius when the keyboard resizes the WebView.
        val unit = web.width.toDouble()
        panel.getLocationInWindow(glassLocation)
        web.getLocationInWindow(pageLocation)
        val geometry = listOf((glassLocation[0] - pageLocation[0]) / unit, (glassLocation[1] - pageLocation[1]) / unit,
            panel.width / unit, panel.height / unit, radius / unit, blur / unit).joinToString(",")
        val args = "$geometry,$dark"
        if (last != args) {
            last = args
            web.evaluateJavascript("window.SugarTaskGlass?.place($args)", null)
        }
    }
    fun align(radiusPx: Int, blurPx: Int, isDark: Boolean) {
        radius = radiusPx
        blur = blurPx
        dark = isDark
        if (!queued && page != null) {
            queued = true
            panel.postOnAnimation(draw)
        }
    }
    fun clear() {
        panel.removeCallbacks(draw)
        queued = false
        if (last.isNotEmpty()) page?.evaluateJavascript("window.SugarTaskGlass?.clear()", null)
        last = ""
    }
}

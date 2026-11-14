package app.dsh.sugar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Supplies the WebView compositor with the task card's transparent foreground, beneath the rail. */
internal class MobileOverlayMirror(
    container: FrameLayout,
    private val root: View,
    private val render: (Canvas) -> Unit,
) {
    private val page = (0 until container.childCount).map(container::getChildAt).filterIsInstance<WebView>().firstOrNull()
    private val origin = page?.url?.let(Uri::parse)?.let { "${it.scheme}://${it.encodedAuthority}" }
    private val rootPosition = IntArray(2)
    private val pagePosition = IntArray(2)
    private var session = 0L
    private var revision = 0L
    private var geometry = ""
    private var drawnWidth = 0
    private var drawnHeight = 0
    private var queued = false
    private var encoding = false
    private var dirty = false

    /** Main thread. Coalesce palette, layout, text and drawable changes into one capture. */
    fun changed() {
        revision++
        dirty = true
        place()
    }

    /** A drag only moves the cached image. Its pixels do not depend on the DSH webpage. */
    fun place() {
        if (page == null || queued) return
        queued = true
        root.postOnAnimation(frame)
    }

    private val frame = Runnable {
        queued = false
        val web = page ?: return@Runnable
        if (!root.isShown || root.width <= 0 || root.height <= 0 || web.width <= 0) return@Runnable
        if (session == 0L) {
            session = nextSession.incrementAndGet()
            dirty = true
        }
        val unit = web.width.toDouble()
        root.getLocationInWindow(rootPosition)
        web.getLocationInWindow(pagePosition)
        val coords = listOf((rootPosition[0] - pagePosition[0]) / unit, (rootPosition[1] - pagePosition[1]) / unit,
            root.width / unit, root.height / unit).joinToString(",")
        if (geometry != coords) {
            geometry = coords
            evaluate("place($session,$coords)")
        }
        if (root.width != drawnWidth || root.height != drawnHeight) dirty = true
        if (!dirty || encoding) return@Runnable

        val id = session
        val version = revision
        drawnWidth = root.width
        drawnHeight = root.height
        val captureWidth = drawnWidth
        val captureHeight = drawnHeight
        val scale = min(1.0, min(1280.0 / maxOf(drawnWidth, drawnHeight),
            sqrt(1_200_000.0 / (drawnWidth.toDouble() * drawnHeight))))
        val bitmap = Bitmap.createBitmap((drawnWidth * scale).roundToInt().coerceAtLeast(1),
            (drawnHeight * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        dirty = false
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(bitmap.width.toFloat() / drawnWidth, bitmap.height.toFloat() / drawnHeight)
            render(canvas)
        } catch (failure: RuntimeException) {
            bitmap.recycle()
            Log.w("SugarTaskMirror", "Could not draw the local task card", failure)
            return@Runnable
        }
        encoding = true
        encoder.execute {
            val encoded = try {
                val bytes = ByteArrayOutputStream()
                if (bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))
                    Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP) else null
            } catch (failure: RuntimeException) {
                Log.w("SugarTaskMirror", "Could not encode the local task card", failure)
                null
            } finally { bitmap.recycle() }
            // WebView.post reaches the UI thread even after this native card was detached.
            web.post {
                encoding = false
                if (session == id && revision == version && !dirty && root.isShown &&
                    root.width == captureWidth && root.height == captureHeight && encoded != null)
                    evaluate("image($id,$version,'$encoded')")
                if (dirty && session != 0L) place()
            }
        }
    }

    private fun evaluate(method: String) {
        val source = origin ?: return
        page?.evaluateJavascript("if (location.origin === ${JSONObject.quote(source)} && window === top) window.SugarTaskMirror?.$method",null)
    }

    fun clear() {
        root.removeCallbacks(frame)
        queued = false
        if (session != 0L) evaluate("clear($session)")
        session = 0L
        revision++
        dirty = false
        geometry = ""
        drawnWidth = 0
        drawnHeight = 0
    }

    private companion object {
        val nextSession = AtomicLong()
        val encoder = ThreadPoolExecutor(0,1,5,TimeUnit.SECONDS,LinkedBlockingQueue()) {
            Thread(it,"sugar-card-png").apply { isDaemon = true }
        }
    }
}

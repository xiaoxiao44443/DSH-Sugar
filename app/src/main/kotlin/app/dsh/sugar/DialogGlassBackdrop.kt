package app.dsh.sugar

import android.annotation.TargetApi
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A local backdrop from the Activity surface. The dialog and its artwork are never in that surface. */
internal class DialogGlassBackdrop(
    activity: Activity,
    private val decor: View,
    private val invalidate: () -> Unit = { decor.invalidate() },
) {
    private val window = activity.window
    private val manager = activity.getSystemService(WindowManager::class.java)
    private val owner = window.decorView
    private val main = Handler(Looper.getMainLooper())
    private val panel = Rect()
    private val clip = Path()
    private val ownerAt = IntArray(2)
    private val dialogAt = IntArray(2)
    private val ownerInWindow = IntArray(2)
    private var offsetX = 0
    private var offsetY = 0
    private var ownerWidth = 0
    private var ownerHeight = 0
    private var corner = 0f
    private var radius = 0f
    private var dirty = false
    private var scheduled = false
    private var copying = false
    private var retriedEmpty = false
    private var awaitingOwnerFrame = false
    private var disposed = false
    private var samplingPaused = false
    private var frozenOffsetX = 0
    private var frozenOffsetY = 0
    private var frozenPanelLeft = 0
    private var frozenPanelTop = 0
    private var generation = 0
    private var lastCopy = 0L
    private var frame: Frame? = null
    private data class Frame(val area: Rect, val node: RenderNode)
    private val copy = Runnable { capture() }
    private val ownerPainted = Runnable {
        awaitingOwnerFrame = false
        request()
    }
    private val ownerDraw = ViewTreeObserver.OnPreDrawListener {
        if (!disposed && !samplingPaused && !awaitingOwnerFrame && radius > 0f && decor.isShown) {
            if (owner.isHardwareAccelerated) {
                // A copy requested at onDraw can still receive the previous Surface buffer.
                awaitingOwnerFrame = true
                owner.viewTreeObserver.registerFrameCommitCallback(ownerPainted)
            } else request()
        }
        true
    }
    private val placement = ViewTreeObserver.OnPreDrawListener { measureOffset(); true }

    init {
        owner.viewTreeObserver.addOnPreDrawListener(ownerDraw)
        decor.viewTreeObserver.addOnPreDrawListener(placement)
    }

    /** Called with the card's bounds in decor coordinates. A zero radius uses the caller's tint alone. */
    fun update(panel: Rect, cornerRadiusPx: Float, blurRadiusPx: Float) {
        if (disposed) return
        val effective = if (Build.VERSION.SDK_INT >= 31 && blurRadiusPx > 0f && manager.isCrossWindowBlurEnabled)
            blurRadiusPx else 0f
        if (effective == radius && this.panel == panel && corner == cornerRadiusPx) return
        if (effective != radius) {
            generation++
            frame = null
            retriedEmpty = false
        }
        radius = effective
        corner = cornerRadiusPx
        this.panel.set(panel)
        clip.rewind()
        if (!panel.isEmpty) clip.addRoundRect(panel.left.toFloat(), panel.top.toFloat(), panel.right.toFloat(),
            panel.bottom.toFloat(), corner, corner, Path.Direction.CW)
        measureOffset()
        request()
    }

    private fun measureOffset() {
        if (disposed || radius <= 0f || !decor.isAttachedToWindow) return
        owner.getLocationOnScreen(ownerAt)
        decor.getLocationOnScreen(dialogAt)
        owner.getLocationInWindow(ownerInWindow)
        val x = dialogAt[0] - ownerAt[0] + ownerInWindow[0]
        val y = dialogAt[1] - ownerAt[1] + ownerInWindow[1]
        if (x == offsetX && y == offsetY && owner.width == ownerWidth && owner.height == ownerHeight) return
        if (owner.width != ownerWidth || owner.height != ownerHeight) {
            generation++
            frame = null
            retriedEmpty = false
        }
        offsetX = x
        offsetY = y
        ownerWidth = owner.width
        ownerHeight = owner.height
        if (frame != null) invalidate()
        request()
    }

    fun setSamplingPaused(paused: Boolean) {
        if (samplingPaused == paused) return
        samplingPaused = paused
        if (paused) {
            frozenOffsetX = offsetX
            frozenOffsetY = offsetY
            frozenPanelLeft = panel.left
            frozenPanelTop = panel.top
            main.removeCallbacks(copy)
            scheduled = false
        } else request()
    }

    private fun request() {
        if (disposed || samplingPaused || radius <= 0f || panel.isEmpty || !decor.isShown) return
        dirty = true
        if (copying || scheduled) return
        scheduled = true
        main.postAtTime(copy, maxOf(SystemClock.uptimeMillis(), lastCopy + 100L))
    }

    private fun capture() {
        scheduled = false
        dirty = false
        if (disposed || samplingPaused || radius <= 0f || !decor.isHardwareAccelerated || !decor.isShown || !owner.isShown ||
            Build.VERSION.SDK_INT < 31) return
        val area = Rect(panel)
        area.offset(offsetX, offsetY)
        // Include neighboring pixels in the convolution right up to the frame.
        val margin = ceil(3f * radius).toInt()
        area.inset(-margin, -margin)
        if (!area.intersect(ownerInWindow[0], ownerInWindow[1], ownerInWindow[0] + ownerWidth,
                ownerInWindow[1] + ownerHeight)) return
        // Keep at most 384×384 RGBA samples, with enough resolution for a soft Gaussian.
        val step = maxOf(1f, radius / 6f, maxOf(area.width(), area.height()) / 384f)
        val pixels = Bitmap.createBitmap((area.width() / step).roundToInt().coerceAtLeast(1),
            (area.height() / step).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val version = generation
        copying = true
        lastCopy = SystemClock.uptimeMillis()
        try {
            PixelCopy.request(window, Rect(area), pixels, { status ->
                copying = false
                if (status == PixelCopy.SUCCESS && !disposed && !samplingPaused && version == generation && radius > 0f) {
                    frame = Frame(area, blurredNode(pixels, area))
                    retriedEmpty = false
                    invalidate()
                    // The render thread may still reference the previous frame. Let its bitmap
                    // follow its display list's lifetime rather than recycling it on the UI thread.
                } else {
                    pixels.recycle()
                    if (status == PixelCopy.ERROR_SOURCE_INVALID && version == generation && frame != null) {
                        frame = null
                        invalidate()
                    }
                }
                if (status == PixelCopy.ERROR_SOURCE_NO_DATA && !disposed && version == generation && !retriedEmpty) {
                    retriedEmpty = true
                    request()
                } else if (dirty) request()
            }, main)
        } catch (_: IllegalArgumentException) {
            copying = false
            pixels.recycle()
            // No backing surface yet; the next Activity draw or dialog layout will retry.
        }
    }

    @TargetApi(31)
    private fun blurredNode(pixels: Bitmap, area: Rect): RenderNode {
        val node = RenderNode("Sugar dialog backdrop")
        node.setPosition(0, 0, pixels.width, pixels.height)
        val canvas = node.beginRecording()
        canvas.drawBitmap(pixels, null, Rect(0, 0, pixels.width, pixels.height), bitmapPaint)
        node.endRecording()
        node.setRenderEffect(RenderEffect.createBlurEffect(radius * pixels.width / area.width(),
            radius * pixels.height / area.height(), Shader.TileMode.CLAMP))
        return node
    }

    /** Draw before the tint and the title, using the same coordinate origin as update(). */
    fun draw(canvas: Canvas) {
        val captured = frame ?: return
        if (!canvas.isHardwareAccelerated || radius <= 0f || panel.isEmpty) return
        val area = captured.area
        val save = canvas.save()
        canvas.clipPath(clip)
        val x = if (samplingPaused) frozenOffsetX - (panel.left - frozenPanelLeft) else offsetX
        val y = if (samplingPaused) frozenOffsetY - (panel.top - frozenPanelTop) else offsetY
        canvas.translate((area.left - x).toFloat(), (area.top - y).toFloat())
        canvas.scale(area.width().toFloat() / captured.node.width, area.height().toFloat() / captured.node.height)
        canvas.drawRenderNode(captured.node)
        canvas.restoreToCount(save)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        main.removeCallbacks(copy)
        if (owner.viewTreeObserver.isAlive) {
            owner.viewTreeObserver.removeOnPreDrawListener(ownerDraw)
            if (awaitingOwnerFrame) owner.viewTreeObserver.unregisterFrameCommitCallback(ownerPainted)
        }
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(placement)
        frame = null
    }

    private companion object {
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}

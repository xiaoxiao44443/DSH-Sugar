package app.dsh.sugar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.view.MotionEvent
import android.widget.FrameLayout

/** Paints the native overlay behind the WebView's rail and lets its sibling receive the initial down. */
internal class TaskOverlayLayout(context: Context) : FrameLayout(context) {
    private var visible = emptyList<TaskRailRegion>()
    private var touch = emptyList<TaskRailRegion>()
    private val cutouts = Path()
    private val location = IntArray(2)

    fun updateNavigation(painted: List<TaskRailRegion>, hitTargets: List<TaskRailRegion>) {
        touch = hitTargets
        if (visible == painted) return
        visible = painted
        cutouts.rewind()
        for (region in painted) {
            if (!region.valid) continue
            cutouts.addRoundRect(region.left, region.top, region.right, region.bottom,
                region.corner, region.corner, Path.Direction.CW)
        }
        invalidate()
    }

    fun drawForMirror(canvas: Canvas) = super.dispatchDraw(canvas)

    override fun dispatchDraw(canvas: Canvas) {
        if (cutouts.isEmpty) {
            super.dispatchDraw(canvas)
            return
        }
        getLocationInWindow(location)
        val x = location[0].toFloat()
        val y = location[1].toFloat()
        val save = canvas.save()
        canvas.translate(-x, -y)
        canvas.clipOutPath(cutouts)
        canvas.translate(x, y)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    fun moved() {
        // Translation alone can reuse Android's display list with the previous local clip.
        if (!cutouts.isEmpty) invalidate()
    }

    private fun overNavigation(event: MotionEvent): Boolean {
        if (touch.isEmpty()) return false
        getLocationInWindow(location)
        return touch.any { it.contains(location[0] + event.x, location[1] + event.y) }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && overNavigation(event)) return false
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_HOVER_EXIT && overNavigation(event)) return false
        return super.dispatchHoverEvent(event)
    }
}

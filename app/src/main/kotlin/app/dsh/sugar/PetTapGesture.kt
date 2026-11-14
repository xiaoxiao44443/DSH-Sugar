package app.dsh.sugar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Deliver the usual click only after a second tap has been ruled out. Dragging never opens the sheet. */
internal class PetTapGesture(
    context: Context,
    private val singleTap: () -> Unit,
    private val doubleTap: () -> Unit,
    private val canDoubleTap: () -> Boolean = { true },
    private val onDown: () -> Unit = {},
    private val onDrag: (Float, Float) -> Unit = { _, _ -> },
    private val onDragEnd: () -> Unit = {},
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private val touch = ViewConfiguration.get(context)
    private val timeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var x = 0f
    private var y = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastUp = -1L
    private var second = false
    private var tracking = false
    private var drag = false
    private val click = Runnable { lastUp = -1L; singleTap() }

    fun cancel() {
        handler.removeCallbacks(click)
        lastUp = -1L
        if (tracking && drag) onDragEnd()
        tracking = false
        second = false
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onDown()
                x = event.rawX
                y = event.rawY
                val dx = x - lastX
                val dy = y - lastY
                second = lastUp >= 0 && event.eventTime - lastUp <= timeout &&
                    dx * dx + dy * dy <= touch.scaledDoubleTapSlop.toFloat() * touch.scaledDoubleTapSlop
                if (lastUp >= 0) {
                    handler.removeCallbacks(click)
                    if (!second) click.run()
                    lastUp = -1L
                }
                drag = false
                tracking = true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (tracking) {
                val dx = event.rawX - x
                val dy = event.rawY - y
                if (dx * dx + dy * dy > touch.scaledTouchSlop.toFloat() * touch.scaledTouchSlop) drag = true
                if (drag) onDrag(dx, dy)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    tracking = false
                    when {
                        drag -> onDragEnd()
                        !canDoubleTap() -> singleTap()
                        second -> doubleTap()
                        else -> {
                            lastX = event.rawX
                            lastY = event.rawY
                            lastUp = event.eventTime
                            handler.postDelayed(click, timeout)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
}

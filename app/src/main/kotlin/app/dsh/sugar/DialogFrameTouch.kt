package app.dsh.sugar

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Decor padding belongs to the transparent frame above and below the glass. */
private fun View.inDialogFrame(x: Float, y: Float) =
    x >= 0 && x < width && y >= 0 && y < height && (y < paddingTop || y >= height - paddingBottom)

internal class DialogFrameTap(private val touchSlop: Int) {
    var tracking = false
        private set
    private var downX = 0f
    private var downY = 0f
    private var eligible = false

    fun down(x: Float, y: Float, inFrame: Boolean): Boolean {
        tracking = inFrame
        eligible = inFrame
        downX = x
        downY = y
        return tracking
    }

    fun move(x: Float, y: Float) {
        if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) eligible = false
    }

    fun additionalPointer() { eligible = false }

    fun finish(x: Float, y: Float, inFrame: Boolean): Boolean {
        move(x, y)
        val tap = tracking && eligible && inFrame
        tracking = false
        return tap
    }

    fun cancel() { tracking = false }
}

// This routes padding taps to the dialog's existing cancellation policy. The padding is decorative.
@SuppressLint("ClickableViewAccessibility")
internal fun installDialogFrameTouch(dialog: AlertDialog, decor: View) {
    val tap = DialogFrameTap(ViewConfiguration.get(decor.context).scaledTouchSlop)
    decor.setOnTouchListener { view, e ->
        if (e.actionMasked == MotionEvent.ACTION_DOWN)
            return@setOnTouchListener tap.down(e.x, e.y, view.inDialogFrame(e.x, e.y))
        if (!tap.tracking) return@setOnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> tap.move(e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> tap.additionalPointer()
            MotionEvent.ACTION_CANCEL -> tap.cancel()
            MotionEvent.ACTION_UP -> if (tap.finish(e.x, e.y, view.inDialogFrame(e.x, e.y))) {
                val outside = MotionEvent.obtain(e)
                try {
                    outside.action = MotionEvent.ACTION_OUTSIDE
                    dialog.onTouchEvent(outside)
                } finally {
                    outside.recycle()
                }
            }
        }
        true
    }
}

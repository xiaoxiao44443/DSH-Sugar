package app.dsh.sugar

import android.annotation.TargetApi
import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback

/** Distinguish a system edge gesture from the back button, including on pre-predictive devices. */
internal class SystemBackEdge(private val activity: Activity) {
    private var touchEdge: String? = null
    private var cancelledEdge: String? = null
    private var cancelledAt = 0L
    val hasCancelledTouch get() = cancelledEdge != null

    fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelledEdge = null
                val reach = 32 * activity.resources.displayMetrics.density
                touchEdge = when {
                    event.x <= reach -> "left"
                    event.x >= activity.window.decorView.width - reach -> "right"
                    else -> null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelledEdge = touchEdge
                cancelledAt = SystemClock.uptimeMillis()
                touchEdge = null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_DOWN -> touchEdge = null
        }
    }

    fun take(reported: String? = null): String? {
        val side = reported ?: cancelledEdge?.takeIf { SystemClock.uptimeMillis() - cancelledAt < 900 }
        cancelledEdge = null
        return side
    }

    @TargetApi(33)
    fun callback(onBack: (String?) -> Unit): OnBackInvokedCallback =
        if (Build.VERSION.SDK_INT >= 34) predictive(onBack) else OnBackInvokedCallback { onBack(take()) }

    @TargetApi(34)
    private fun predictive(onBack: (String?) -> Unit): OnBackInvokedCallback = object : OnBackAnimationCallback {
        private var side: String? = null
        override fun onBackStarted(event: BackEvent) {
            side = if (!event.touchY.isFinite() || event.touchY <= 0f) null else when (event.swipeEdge) {
                BackEvent.EDGE_LEFT -> "left"
                BackEvent.EDGE_RIGHT -> "right"
                else -> null
            }
        }
        override fun onBackCancelled() { side = null; cancelledEdge = null }
        override fun onBackInvoked() { val edge = take(side); side = null; onBack(edge) }
    }
}

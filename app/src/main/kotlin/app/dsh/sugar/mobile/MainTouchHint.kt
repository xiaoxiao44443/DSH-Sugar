package app.dsh.sugar.mobile

import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Optional, non-interactive indicator for an accepted tap on the main display. */
internal class MainTouchHint(private val service: MobileAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    @Volatile private var pending = false
    private val windows get() = service.getSystemService(WindowManager::class.java)
    private val remove = Runnable { dismiss() }

    fun show(x: Float, y: Float, pkg: String) {
        // The service has already dispatched the action; this only filters decorative feedback.
        // Omitting a ring on system/permission screens neither permits nor rejects an operation.
        if (pkg.isEmpty() || pkg == "com.android.systemui" || pkg.contains("permissioncontroller") ||
            service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        pending = true
        main.post {
            dismiss()
            val density = service.resources.displayMetrics.density
            val size = (34 * density).roundToInt()
            val ring = View(service).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isFocusable = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(48, 72, 133, 255))
                    setStroke(maxOf(1, (2 * density).roundToInt()), Color.argb(210, 68, 122, 235))
                }
            }
            val layout = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                this.x = x.roundToInt() - size / 2
                this.y = y.roundToInt() - size / 2
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (runCatching { windows.addView(ring, layout) }.isSuccess) {
                pending = true
                view = ring
                ring.animate().alpha(0f).setDuration(240).start()
                main.postDelayed(remove, 260)
            }
        }
    }

    /** Allow one full display interval for the target's post-input redraw and any overlay removal. */
    fun beforeObservation(awaitRedraw: Boolean) {
        if (!pending && !awaitRedraw) return
        val done = CompletableFuture<Unit>()
        main.post {
            if (pending) dismiss()
            val frames = Choreographer.getInstance()
            frames.postFrameCallback { frames.postFrameCallback { done.complete(Unit) } }
        }
        try { done.get(1, TimeUnit.SECONDS) } catch (_: java.util.concurrent.TimeoutException) {
            throw ScreenProblem("display_unavailable", "The main display has not finished updating.")
        }
    }

    fun dismiss() {
        main.removeCallbacks(remove)
        view?.let {
            it.animate().cancel()
            runCatching { windows.removeViewImmediate(it) }
        }
        view = null
        pending = false
    }
}

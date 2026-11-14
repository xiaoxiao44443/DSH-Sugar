package app.dsh.sugar

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager

/** A foreground window owns its keep-on flag and dim override only while an agent turn is active. */
internal class TaskScreenDimmer(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE)
    private val resolver = activity.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val checkDeadline = Runnable { update() }
    private val timeoutObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            timeoutMs = readTimeout()
            update()
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == PREFERENCE) {
            enabled = prefs.getBoolean(PREFERENCE, true)
            update()
        }
    }
    private var resumed = false
    private var focused = false
    private var taskActive = false
    private var enabled = true
    private var lastInput = 0L
    private var timeoutMs = 0L
    private var holding = false
    private var addedKeepOn = false
    private var dimmed = false
    private var previousBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    fun resume(active: Boolean) {
        if (resumed) return
        resumed = true
        focused = activity.hasWindowFocus()
        taskActive = active
        lastInput = SystemClock.uptimeMillis()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, timeoutObserver)
        enabled = prefs.getBoolean(PREFERENCE, true)
        timeoutMs = readTimeout()
        update()
    }

    fun pause() {
        if (!resumed) return
        resumed = false
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        resolver.unregisterContentObserver(timeoutObserver)
        update()
    }

    fun focusChanged(hasFocus: Boolean) {
        if (!resumed || focused == hasFocus) return
        focused = hasFocus
        if (hasFocus) lastInput = SystemClock.uptimeMillis()
        update()
    }

    fun taskChanged(active: Boolean) {
        if (!resumed || taskActive == active) return
        taskActive = active
        update()
    }

    fun interacted() {
        if (!resumed || !focused) return
        lastInput = SystemClock.uptimeMillis()
        // The scheduled callback will read the latest input time; scrolling needn't rearm it per frame.
        if (dimmed) update()
    }

    fun touch(event: MotionEvent) {
        // Activity.onUserInteraction already covers the initial down as well as hardware keys.
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) interacted()
    }

    private fun readTimeout() = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, -1).toLong()

    private fun setDimmed(dim: Boolean) {
        if (dimmed == dim) return
        val window = activity.window
        val attributes = window.attributes
        if (dim) {
            previousBrightness = attributes.screenBrightness
            val level = if (previousBrightness >= 0f) previousBrightness else {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 255).coerceIn(1, 255) / 255f
            }
            attributes.screenBrightness = minOf(DIM_BRIGHTNESS, level)
        } else {
            attributes.screenBrightness = previousBrightness
        }
        window.attributes = attributes
        dimmed = dim
    }

    private fun update() {
        main.removeCallbacks(checkDeadline)
        val window = activity.window
        val keep = resumed && focused && enabled && taskActive
        if (!keep) {
            setDimmed(false)
            if (holding && addedKeepOn) window.clearFlags(KEEP_ON)
            holding = false
            return
        }
        if (!holding) {
            addedKeepOn = (window.attributes.flags and KEEP_ON) == 0
            if (addedKeepOn) window.addFlags(KEEP_ON)
            holding = true
        }
        // Some devices use a nonpositive timeout for "never". Don't invent a timeout in that case.
        val deadline = lastInput + timeoutMs
        val dim = timeoutMs > 0 && SystemClock.uptimeMillis() >= deadline
        setDimmed(dim)
        if (!dim && timeoutMs > 0) main.postAtTime(checkDeadline, deadline)
    }

    companion object {
        const val PREFERENCE = "dimScreenDuringTasks"
        private const val DIM_BRIGHTNESS = 0.10f
        private const val KEEP_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    }
}

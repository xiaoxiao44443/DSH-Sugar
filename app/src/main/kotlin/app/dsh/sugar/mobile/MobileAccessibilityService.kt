@file:Suppress("DEPRECATION")

package app.dsh.sugar.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.dsh.sugar.runtime.MobileUseTasks
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Local automation uses the accessibility capability the device user enables in Android.
 * No hard-coded app denylist is imposed, preserving the user's device-management choices;
 * Android's capability checks and the task's session/control checks still apply.
 */
class MobileAccessibilityService : AccessibilityService() {
    private val windowChanges = MainWindowChanges()
    private val touchHint by lazy { MainTouchHint(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        MobileScreen.connected(this)
        MobileUseTasks.deviceChanged("main")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // WebView uses subscribed event types to keep its virtual node tree current.
        // Content, text and scroll events update accessibility caches without replacing the window.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val displayId = if (Build.VERSION.SDK_INT >= 33) event.displayId else Display.INVALID_DISPLAY
        windowChanges.changed(displayId, event.windowId, event.eventType, event.windowChanges)
    }

    override fun onInterrupt() {
        // Android is interrupting feedback. The service and its screen session are still bound.
        touchHint.dismiss()
    }
    override fun onUnbind(intent: Intent?): Boolean {
        touchHint.dismiss()
        MobileScreen.disconnected(this)
        MobileUseTasks.deviceChanged("main")
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        touchHint.dismiss()
        MobileScreen.disconnected(this)
        MobileUseTasks.deviceChanged("main")
        super.onDestroy()
    }

    private fun <T> withWindow(block: (MainWindow, AccessibilityNodeInfo?) -> T): T {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw ScreenProblem("display_unavailable", "The main display is unavailable.")
        val size = Point()
        display.getRealSize(size)
        // getRootInActiveWindow can select a virtual screen. getWindows() enumerates the default display.
        val windows = getWindows()
        val info = windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it.isFocused }
        // Android returns windows front-to-back. Include overlays above the target in its version.
        val relevant = if (info == null) emptyList() else windows.take(windows.indexOf(info) + 1).map { it.id }
        val revision = windowChanges.snapshot(relevant)
        val root = try { info?.root } catch (e: Exception) {
            windows.forEach { it.recycle() }
            throw e
        }
        return try {
            val window = try {
                val bounds = info?.let { value ->
                    MainWindowBounds.from(Rect().also { value.getBoundsInScreen(it) })
                }
                val contentBounds = root?.let { value ->
                    MainWindowBounds.from(Rect().also { value.getBoundsInScreen(it) })
                }
                MainWindow(info?.id ?: -1, root?.packageName?.toString().orEmpty(), size.x, size.y,
                    display.rotation * 90, revision, bounds, contentBounds, info?.isFocused, info?.layer)
            } finally { windows.forEach { it.recycle() } }
            block(window, root)
        } finally { root?.recycle() }
    }

    internal fun supportsScreenshot(): Boolean = Build.VERSION.SDK_INT >= 30 &&
        ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0

    internal fun observe(args: JSONObject, historyPreview: Boolean = false, checkActive: () -> Unit): MainObservation {
        val treeRequested = args.optBoolean("tree", true)
        val imageRequested = args.optBoolean("image", false)
        if (!treeRequested && !imageRequested) throw ScreenProblem("invalid_argument", "Request a tree or a screenshot.")
        if (imageRequested && !supportsScreenshot())
            throw ScreenProblem("unsupported", "This accessibility service does not have screenshot capability.")
        touchHint.beforeObservation(imageRequested && args.optBoolean("await_feedback_redraw"))
        checkActive()
        val id = UUID.randomUUID().toString()
        val time = SystemClock.elapsedRealtime()
        val tree = MainTree()
        val json = JSONObject().put("ok", true).put("display", "main").put("observation_id", id)
            .put("timestamp_ms", System.currentTimeMillis())
        val window = withWindow { current, root ->
            if (treeRequested) json.put("tree_timestamp_ms", System.currentTimeMillis()).put("tree", tree.read(root))
            current
        }
        fun validateWindow() = withWindow { current, _ ->
            if (window != current) throw ScreenProblem("stale_observation", "The active window changed during observation.")
        }
        if (imageRequested && Build.VERSION.SDK_INT >= 30) {
            val image = MainScreenImage.capture(this, window, ::validateWindow, args, historyPreview)
            json.put("image_timestamp_ms", image.getLong("timestamp_ms")).put("image", image)
            if (image.has("feedback_delay_ms")) json.put("feedback_delay_ms", image.remove("feedback_delay_ms"))
        } else validateWindow()
        json.put("width", window.width).put("height", window.height).put("rotation", window.rotation)
            .put("package", window.packageName).put("window_id", window.id)
        window.metadata()?.let { json.put("window", it) }
        return MainObservation(id, time, window, tree.nodes, json)
    }

    internal fun act(op: String, args: JSONObject, observation: MainObservation?, active: () -> Boolean,
                     submit: (() -> Boolean) -> Boolean): JSONObject = withWindow { current, root ->
        if (observation != null && (observation.window != current || current.id < 0)) {
            throw ScreenProblem("stale_observation", "The display, rotation, or active window changed.")
        }
        if (op == "open_app" && args.getString("package") == current.packageName && current.id >= 0 &&
            getSystemService(android.os.UserManager::class.java).userProfiles.size == 1) {
            return@withWindow JSONObject().put("ok", true).put("dispatch", "already_foreground").put("verification", "verified")
        }
        if (op == "open_app" || op == "launch") return@withWindow launchApp(args, op == "open_app", submit)
        var gestureResult: CompletableFuture<Boolean>? = null
        var gestureTimeoutMs = 0L
        var touchLocation: Pair<Float, Float>? = null
        fun startGesture(stroke: GestureDescription.StrokeDescription,
                         movement: GestureDescription.StrokeDescription? = null, holdMs: Long = 0): Boolean {
            val completion = CompletableFuture<Boolean>()
            val listener = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { completion.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { completion.complete(false) }
            }
            gestureResult = completion
            gestureTimeoutMs = stroke.duration + holdMs + (movement?.duration ?: 0L) + 1500
            if (movement == null) return submit { dispatchStroke(stroke, listener) }

            val held = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // A stationary continued stroke can complete as soon as DOWN is delivered.
                    // Keep the contact down for real elapsed time before dispatching any MOVE.
                    val handler = Handler(Looper.getMainLooper())
                    val deadline = SystemClock.uptimeMillis() + holdMs
                    val continueDrag = object : Runnable {
                        override fun run() {
                            val remaining = deadline - SystemClock.uptimeMillis()
                            val continued = runCatching {
                                if (!active() || completion.isDone) return@runCatching false
                                if (remaining > 0) return@runCatching handler.postDelayed(this, minOf(remaining, 50L))
                                withWindow { live, _ ->
                                    live.packageName == current.packageName && live.rotation == current.rotation &&
                                        live.width == current.width && live.height == current.height &&
                                        live.bounds == current.bounds && live.contentBounds == current.contentBounds &&
                                        submit { dispatchStroke(movement, listener) }
                                }
                            }.getOrDefault(false)
                            if (!continued) {
                                runCatching { dispatchStroke(stroke.continueStroke(stroke.path, 0, 1, false), null) }
                                completion.complete(false)
                            }
                        }
                    }
                    continueDrag.run()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) { completion.complete(false) }
            }
            return submit { dispatchStroke(stroke, held) }
        }
        fun gesture(path: Path, duration: Long) = startGesture(GestureDescription.StrokeDescription(path, 0, duration))
        fun point(xKey: String, yKey: String): Pair<Float, Float> {
            var x = args.getDouble(xKey)
            var y = args.getDouble(yKey)
            when (args.optString("space", "display")) {
                "image" -> {
                    val image = observation?.json?.optJSONObject("image")
                        ?: throw ScreenProblem("invalid_argument", "This observation has no screenshot.")
                    x = image.optDouble("offset_x", 0.0) + x * image.getDouble("scale_x")
                    y = image.optDouble("offset_y", 0.0) + y * image.getDouble("scale_y")
                }
                "display" -> Unit
                else -> throw ScreenProblem("invalid_argument", "The coordinate space must be display or image.")
            }
            if (!x.isFinite() || !y.isFinite() || x < 0 || x >= current.width || y < 0 || y >= current.height)
                throw ScreenProblem("invalid_argument", "Coordinates are outside the main display.")
            return x.toFloat() to y.toFloat()
        }
        fun duration(fallback: Long): Long {
            val value = if (args.has("duration_ms")) args.getLong("duration_ms") else fallback
            if (value !in 1L..GestureDescription.getMaxGestureDuration())
                throw ScreenProblem("invalid_argument", "Invalid gesture duration.")
            return value
        }
        val sent = when (op) {
            "back" -> submit { performGlobalAction(GLOBAL_ACTION_BACK) }
            "home" -> submit { performGlobalAction(GLOBAL_ACTION_HOME) }
            "recents" -> submit { performGlobalAction(GLOBAL_ACTION_RECENTS) }
            "swipe" -> {
                val (x, y) = point("x", "y")
                val (x2, y2) = point("x2", "y2")
                gesture(Path().apply { moveTo(x, y); lineTo(x2, y2) }, duration(350))
            }
            "drag" -> {
                if (args.has("node_id")) throw ScreenProblem("invalid_argument", "Drag requires start and end coordinates.")
                val (x, y) = point("x", "y")
                val (x2, y2) = point("x2", "y2")
                fun dragTime(key: String, fallback: Long): Long {
                    if (!args.has(key)) return fallback
                    val ms = (args.opt(key) as? Number)?.toDouble() ?: Double.NaN
                    if (ms !in 1.0..3000.0 || ms != ms.toLong().toDouble())
                        throw ScreenProblem("invalid_argument", "Drag hold and movement must each be whole milliseconds in 1–3000.")
                    return ms.toLong()
                }
                val hold = dragTime("hold_ms", 650)
                val travel = dragTime("duration_ms", 350)
                val start = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1, true)
                val path = Path().apply { moveTo(x, y); lineTo(x2, y2) }
                startGesture(start, start.continueStroke(path, 0, travel, false), hold)
            }
            "tap", "long_press", "input" -> {
                if (args.has("node_id")) {
                    val ref = observation?.nodes?.get(args.getString("node_id"))
                        ?: throw ScreenProblem("stale_target", "The node is not part of this observation.")
                    withTarget(root, ref) { target ->
                        if (op == "tap" || op == "long_press")
                            touchLocation = ref.bounds.exactCenterX() to ref.bounds.exactCenterY()
                        when {
                            op == "input" -> {
                                if (!target.isEditable) throw ScreenProblem("invalid_target", "This node is not editable.")
                                val text = args.getString("text")
                                val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                                submit { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle) }
                            }
                            op == "tap" && target.isClickable -> submit { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                            op == "long_press" && target.isLongClickable && !args.has("duration_ms") ->
                                submit { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
                            else -> {
                                val visible = Rect(ref.bounds)
                                if (!visible.intersect(0, 0, current.width, current.height))
                                    throw ScreenProblem("invalid_target", "The node has no visible bounds.")
                                gesture(Path().apply { moveTo(visible.exactCenterX(), visible.exactCenterY()) },
                                    duration(if (op == "long_press") 650 else 55))
                            }
                        }
                    }
                } else {
                    if (op == "input") throw ScreenProblem("invalid_argument", "Input requires an editable node.")
                    val (x, y) = point("x", "y")
                    touchLocation = x to y
                    gesture(Path().apply { moveTo(x, y) }, duration(if (op == "long_press") 650 else 55))
                }
            }
            else -> throw ScreenProblem("unsupported", "Unknown phone operation.")
        }
        if (!sent) throw ScreenProblem("dispatch_rejected", "Android did not accept the action.")
        if (touchLocation != null && MobileUseTasks.showTouches(this)) {
            touchLocation?.let { (x, y) -> touchHint.show(x, y, current.packageName) }
        }
        val receipt = JSONObject().put("ok", true).put("dispatch", "sent").put("verification", "unknown")
            .put("action_id", UUID.randomUUID().toString())
        gestureResult?.let { completion ->
            val status = try {
                if (completion.get(gestureTimeoutMs, TimeUnit.MILLISECONDS)) "completed" else "cancelled"
            } catch (_: TimeoutException) {
                completion.complete(false)
                "unknown"
            }
            receipt.put("gesture", status)
        }
        receipt
    }

    private fun dispatchStroke(stroke: GestureDescription.StrokeDescription, callback: GestureResultCallback?): Boolean {
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(Display.DEFAULT_DISPLAY)
        return dispatchGesture(builder.build(), callback, null)
    }

    private fun launchApp(args: JSONObject, byName: Boolean, submit: (() -> Boolean) -> Boolean): JSONObject {
        val pkg = args.getString("package")
        if (pkg.length > 255 || !PACKAGE_NAME.matches(pkg))
            throw ScreenProblem("invalid_argument", "Invalid Android package name.")
        val requested = args.optString("activity")
        val intent = if (requested.isEmpty()) {
            packageManager.getLaunchIntentForPackage(pkg)
                ?: throw ScreenProblem("app_unavailable", "This package has no accessible launcher activity.")
        } else {
            val name = if (requested.startsWith(".")) pkg + requested else requested
            if (name.length > 512 || !CLASS_NAME.matches(name))
                throw ScreenProblem("invalid_argument", "Specify a valid full or package-relative Android activity class.")
            val component = ComponentName(pkg, name)
            val info = try { packageManager.getActivityInfo(component, 0) } catch (_: PackageManager.NameNotFoundException) {
                throw ScreenProblem("app_unavailable", "The requested activity is not accessible.")
            }
            // Same-package launches use Sugar's UID and may reach its internal activities.
            // This does not export them to other UIDs or bypass Android's component checks.
            if (!info.enabled || !info.applicationInfo.enabled || (!info.exported && pkg != packageName))
                throw ScreenProblem("app_unavailable", "The requested activity cannot be opened.")
            if (byName) Intent.makeMainActivity(component) else Intent().setComponent(component)
        }
        try {
            submit {
                val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
                true
            }
        } catch (_: ActivityNotFoundException) {
            throw ScreenProblem("app_unavailable", "Android could not find the requested activity.")
        }
        // Android may silently suppress a background launch. Only the active package verifies it.
        val deadline = SystemClock.elapsedRealtime() + 2500
        var reached = withWindow { window, _ -> window.packageName == pkg }
        while (!reached && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            reached = withWindow { window, _ -> window.packageName == pkg }
        }
        return JSONObject().put("ok", true).put("dispatch", "sent").put("action_id", UUID.randomUUID().toString())
            .put("verification", if (reached) "verified" else "unknown").put("package", pkg)
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")
        val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
    }
}

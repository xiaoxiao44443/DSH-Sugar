package app.dsh.sugar

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.sugar.runtime.MobileUseTasks
import app.dsh.sugar.runtime.MobileUseTasks.VirtualTask
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Service-owned return control. Existing overlay permission is optional; the service notice remains an entry point. */
internal class VirtualTakeoverOverlay(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sugar-virtual-return").apply { isDaemon = true } }
    private val foregroundWorker = Executors.newSingleThreadExecutor { Thread(it, "sugar-virtual-foreground").apply { isDaemon = true } }
    private val foregroundPolling = VirtualForegroundPolling()
    private var foregroundScheduled = false
    private val foregroundPoll = Runnable {
        foregroundScheduled = false
        if (!running || window?.attached != true || !Settings.canDrawOverlays(context) ||
            !power.isInteractive || keyguard.isKeyguardLocked) {
            selectForegroundTarget(null)
        } else {
            val request = foregroundPolling.begin()
            if (request != null) {
                val bounds = window?.appearanceBounds()
                foregroundWorker.execute {
                    val sample = runCatching { MobileUseTasks.refreshVirtualForeground(
                        request.target.conversation, request.target.virtualKey, bounds) }.getOrNull()
                    main.post {
                        val current = foregroundPolling.finish(request)
                        if (running) {
                            if (current) {
                                window?.sampleAppearance(sample, bounds)
                                refresh()
                            }
                            scheduleForegroundPoll()
                        }
                    }
                }
            }
        }
    }
    private val ops = context.getSystemService(AppOpsManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val refreshTask = Runnable { refresh() }
    private val changed: () -> Unit = { main.removeCallbacks(refreshTask); main.post(refreshTask); Unit }
    private val permissionChanged = AppOpsManager.OnOpChangedListener { _, _ -> changed() }
    private val screenChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
            // The unlocked window token may become available just after USER_PRESENT is delivered.
            if (intent.action != Intent.ACTION_SCREEN_OFF) main.postDelayed(refreshTask, 350L)
        }
    }
    private var running = false
    private var stopPaletteListener: (() -> Unit)? = null
    private var window: ControlWindow? = null
    private var pendingAction: VirtualControlAction? = null
    private var positionX: Int? = null
    private var positionY: Int? = null
    private data class PresentationTarget(val conversation: String, val task: String, val displayId: Int?, val project: String)
    private var presentationTarget: PresentationTarget? = null
    private var collapsed = false
    private data class ResizeAnchor(val right: Boolean, val bottom: Boolean, val x: Int, val y: Int)

    fun start() {
        if (running) return
        running = true
        val events = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screenChanged, events, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(screenChanged, events)
        ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permissionChanged)
        MobileUseTasks.addUiListener(changed)
        stopPaletteListener = SugarOverlayPalette.listen(context, changed)
        refresh()
    }

    fun refresh() {
        if (!running) return
        val target = MobileUseTasks.activeVirtualScreens().firstOrNull { it.takeoverOverlayVisible }
        val nextTarget = target?.let { PresentationTarget(it.conversation, it.taskId, it.displayId, it.project) }
        if (presentationTarget != nextTarget) {
            presentationTarget = nextTarget
            collapsed = false
            removeWindow()
        }
        // Busy and error states must expose their status and recovery actions.
        if (target?.controlState != "user" || pendingAction != null) collapsed = false
        if (target == null || !Settings.canDrawOverlays(context) || !power.isInteractive || keyguard.isKeyguardLocked) {
            removeWindow()
            return
        }
        try {
            val control = window ?: run {
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
                ControlWindow(display).also { window = it }
            }
            control.show(target)
            selectForegroundTarget(target.takeIf { pendingAction == null && it.controlState == "user" &&
                it.phase in setOf("active", "parked") }?.let { VirtualPreviewTarget(it.conversation, it.taskId) })
        } catch (_: WindowManager.BadTokenException) {
            removeWindow()
        } catch (_: WindowManager.InvalidDisplayException) {
            removeWindow()
        } catch (_: SecurityException) {
            removeWindow()
        }
    }

    private fun selectForegroundTarget(target: VirtualPreviewTarget?) {
        if (!foregroundPolling.select(target)) return
        main.removeCallbacks(foregroundPoll)
        foregroundScheduled = false
        scheduleForegroundPoll()
    }

    private fun scheduleForegroundPoll() {
        if (!running || foregroundPolling.target == null || foregroundScheduled) return
        foregroundScheduled = true
        main.postDelayed(foregroundPoll, 1000L)
    }

    private fun removeWindow() {
        selectForegroundTarget(null)
        window?.remove()
        window = null
    }

    private fun perform(target: VirtualTask, action: VirtualControlAction) {
        val live = target.currentScreen(MobileUseTasks.activeVirtualScreens()) ?: return
        if (pendingAction != null || !live.allowsControlAction(action)) return
        val pending = VirtualPreviewTarget(live.conversation, live.taskId)
        pendingAction = action
        refresh()
        worker.execute {
            val result = runCatching {
                when (action) {
                    VirtualControlAction.FOCUS -> MobileUseTasks.focusVirtualScreen(live.conversation, live.taskId)
                    VirtualControlAction.RESTORE -> MobileUseTasks.restoreVirtualScreen(live.conversation, live.taskId)
                    VirtualControlAction.RETRY_CLOSE -> {
                        MobileUseTasks.stopVirtualScreen(live.conversation, live.taskId)
                        JSONObject().put("ok", true)
                    }
                }
            }.getOrElse { JSONObject().put("ok", false).put("error", JSONObject().put("message", it.message)) }
            main.post {
                pendingAction = null
                if (!running) return@post
                refresh()
                if (result.optBoolean("ok")) {
                    if (action == VirtualControlAction.RESTORE && MobileUseTasks.activeVirtualScreens().any {
                            it.conversation == live.conversation && it.taskId == live.taskId && it.previewCaptureAllowed
                        } && !pendingPreview.request(pending))
                        runCatching { context.startActivity(AgentNotifications.sessionIntent(context, live.conversation)) }
                } else {
                    val error = result.optJSONObject("error")
                    val message = if (error?.optString("code") == "task_gone") context.sugarString(R.string.mobile_virtual_app_closed)
                        else error?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: context.sugarString(R.string.mobile_virtual_control_failed)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun close() {
        if (!running) return
        running = false
        MobileUseTasks.removeUiListener(changed)
        stopPaletteListener?.invoke()
        stopPaletteListener = null
        context.unregisterReceiver(screenChanged)
        ops.stopWatchingMode(permissionChanged)
        main.removeCallbacks(refreshTask)
        removeWindow()
        worker.shutdown()
        foregroundWorker.shutdown()
    }

    private inner class ControlWindow(display: Display) {
        private val ui = if (Build.VERSION.SDK_INT >= 30)
            context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        else context
        private val wm = ui.getSystemService(WindowManager::class.java)
        private fun dp(value: Int) = (value * ui.resources.displayMetrics.density).roundToInt()
        private val dialog = android.app.Dialog(ui, R.style.SugarTakeoverOverlay)
        private val panelWindow = checkNotNull(dialog.window)
        private val shadow = TakeoverShadowDrawable(ui)
        private val side = shadow.sideInsetPx
        private var folded = false
        private val headroom get() = dp(if (folded) 28 else 34)
        private val cornerRadius get() = dp(if (folded) 22 else 20).toFloat()
        private val bodyWidth get() = dp(if (folded) 184 else 244)
        private val fallbackHeight get() = dp(if (folded) 44 else 92)
        private var resizeAnchor: ResizeAnchor? = null
        private var resizingHeight: Int? = null
        private var disposed = false
        private var target: VirtualTask? = null
        private var sampledSurface: Int? = null
        private var sampledDark: Boolean? = null
        private var lastStyle: List<Any>? = null
        private var dragging = false
        private var tracking = false
        private var blurListening = false
        private val blurChanged = java.util.function.Consumer<Boolean> { if (!disposed) paint() }
        private val params = WindowManager.LayoutParams(
            dp(244), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = positionX ?: side
            y = positionY ?: dp(118)
            title = "Sugar virtual screen controls"
            windowAnimations = 0
        }
        private val decoration = FrameLayout(ui).apply {
            clipChildren = false
            clipToPadding = false
            background = shadow
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val card = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(1))
        }
        // Only the card is a window/blur/touch region. The compositor's surface insets hold
        // the cached shadow and illustration without blurring a large transparent rectangle.
        private val root = object : FrameLayout(ui) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val content = if (folded) capsule else card
                measureChildWithMargins(content, widthMeasureSpec, 0, heightMeasureSpec, 0)
                setMeasuredDimension(resolveSize(content.measuredWidth, widthMeasureSpec),
                    resolveSize(content.measuredHeight, heightMeasureSpec))
                decoration.measure(MeasureSpec.makeMeasureSpec(measuredWidth + side * 2, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight + headroom + shadow.bottomInsetPx, MeasureSpec.EXACTLY))
            }
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                decoration.layout(-side, -headroom, width + side, height + shadow.bottomInsetPx)
                (if (folded) capsule else card).layout(0, 0, width, height)
                shadow.setCardBounds(android.graphics.Rect(side, headroom, side + width, headroom + height), cornerRadius)
            }
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                changed()
            }
        }.apply { clipChildren = false; clipToPadding = false }
        val attached get() = dialog.isShowing && root.isAttachedToWindow && root.isShown
        private val header = LinearLayout(ui).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            minimumHeight = dp(40)
            setPadding(dp(4), 0, dp(4), 0)
        }
        private val icon = ImageView(ui).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val appName = TextView(ui).apply {
            textSize = 13f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        private val status = TextView(ui).apply {
            textSize = 11.5f
            includeFontPadding = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val mascot = ImageView(ui).apply {
            setImageResource(R.drawable.mobile_mascot_peek)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val collapseGlyph = MobileControlIcon(MobileIcon.FOLD, context.getColor(R.color.sugar_ink))
        private val collapseButton = ImageView(ui).apply {
            setImageDrawable(collapseGlyph)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isFocusable = true
            contentDescription = context.sugarString(R.string.mobile_virtual_control_collapse)
            tooltipText = contentDescription
            setOnClickListener { requestCollapsed(true) }
        }
        private val compactIcon = ImageView(ui).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val compactName = TextView(ui).apply {
            textSize = 13f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val expandGlyph = MobileControlIcon(MobileIcon.EXPAND, context.getColor(R.color.sugar_ink))
        private val capsule = object : LinearLayout(ui) {
            override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = android.widget.Button::class.java.name
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            isFocusable = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(compactIcon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
            addView(compactName, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(ui).apply {
                setImageDrawable(expandGlyph)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) })
            setOnClickListener { requestCollapsed(false) }
        }
        private val focusButton = TakeoverActionControl(ui, MobileIcon.PREVIEW, false)
        private val returnButton = TakeoverActionControl(ui, MobileIcon.DISPLAYS, true)
        private val actionDivider = View(ui).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private var iconPackage: String? = null
        private var iconLoaded = false
        private var loadedName: String? = null
        private var moveQueued = false
        private val move = Runnable {
            moveQueued = false
            fit()
            if (attached) panelWindow.attributes = params
        }

        init {
            val labels = LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(appName, LinearLayout.LayoutParams(-1, -2))
                addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
            header.addView(icon, LinearLayout.LayoutParams(dp(27), dp(27)).apply { marginEnd = dp(8) })
            header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(collapseButton, LinearLayout.LayoutParams(dp(40), dp(40)))
            card.addView(header, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(ui).apply {
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
                addView(focusButton, LinearLayout.LayoutParams(0, -2, 1.15f))
                addView(actionDivider, LinearLayout.LayoutParams(dp(1), dp(16)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                })
                addView(returnButton, LinearLayout.LayoutParams(0, -2, 1f))
            }
            card.addView(actions, LinearLayout.LayoutParams(-1, -2))
            decoration.addView(mascot, FrameLayout.LayoutParams(dp(67), dp(40), Gravity.TOP or Gravity.END).apply {
                marginEnd = side + dp(9)
            })
            root.addView(decoration, FrameLayout.LayoutParams(0, 0))
            root.addView(card, FrameLayout.LayoutParams(-1, -2))
            root.addView(capsule, FrameLayout.LayoutParams(-1, dp(44)))
            panelWindow.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.setContentView(root)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            panelWindow.attributes = params
            panelWindow.setDimAmount(0f)
            panelWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            panelWindow.decorView.setPadding(0, 0, 0, 0)
            panelWindow.decorView.clipToOutline = false
            // Reserve enough Surface pixels for the 34dp illustration and broad shadow.
            panelWindow.setElevation(dp(32).toFloat())
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                resizingHeight = null
                if (attached && fit()) panelWindow.attributes = params
                resizeAnchor = null
            }
            val slop = ViewConfiguration.get(ui).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var originX = 0
            var originY = 0
            val drag = View.OnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        originX = params.x; originY = params.y
                        dragging = false; tracking = true
                    }
                    MotionEvent.ACTION_MOVE -> if (tracking) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (maxOf(abs(dx), abs(dy)) > slop) dragging = true
                        if (dragging) {
                            params.x = originX + dx.roundToInt()
                            params.y = originY + dy.roundToInt()
                            if (!moveQueued) { moveQueued = true; root.postOnAnimation(move) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val withinTap = maxOf(abs(event.rawX - downX), abs(event.rawY - downY)) <= slop
                        val clicked = tracking && !dragging && withinTap && folded && view === capsule
                        endDrag()
                        if (clicked) view.performClick()
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> endDrag()
                }
                true
            }
            header.setOnTouchListener(drag)
            capsule.setOnTouchListener(drag)
        }

        private fun endDrag() {
            tracking = false
            root.removeCallbacks(move)
            if (moveQueued) move.run()
            dragging = false
        }

        private fun requestCollapsed(next: Boolean) {
            if (disposed || (next && (target?.controlState != "user" || pendingAction != null))) return
            collapsed = next
            updatePresentation(next)
        }

        @Suppress("DEPRECATION")
        private fun usableSize(): android.util.Size {
            if (Build.VERSION.SDK_INT >= 30) {
                val metrics = wm.currentWindowMetrics
                val safe = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                return android.util.Size((metrics.bounds.width() - safe.left - safe.right).coerceAtLeast(1),
                    (metrics.bounds.height() - safe.top - safe.bottom).coerceAtLeast(1))
            }
            val metrics = ui.resources.displayMetrics
            val safe = root.rootWindowInsets
            return android.util.Size((metrics.widthPixels - (safe?.stableInsetLeft ?: 0) - (safe?.stableInsetRight ?: 0)).coerceAtLeast(1),
                (metrics.heightPixels - (safe?.stableInsetTop ?: 0) - (safe?.stableInsetBottom ?: 0)).coerceAtLeast(1))
        }

        private fun updatePresentation(next: Boolean) {
            if (folded == next) return
            endDrag()
            val space = usableSize()
            if (attached) {
                val right = params.x + root.width / 2 > space.width / 2
                val bottom = params.y + root.height / 2 > space.height / 2
                resizeAnchor = ResizeAnchor(right, bottom,
                    if (right) params.x + root.width else params.x,
                    if (bottom) params.y + root.height else params.y - headroom)
            }
            folded = next
            card.visibility = if (folded) View.GONE else View.VISIBLE
            capsule.visibility = if (folded) View.VISIBLE else View.GONE
            mascot.layoutParams = (mascot.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(if (folded) 56 else 67)
                height = dp(if (folded) 34 else 40)
            }
            // Measure only on a mode switch, so the first clamp uses the new height rather than the old card.
            val width = minOf(bodyWidth, (space.width - side * 2).coerceAtLeast(1))
            val content = if (folded) capsule else card
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                if (folded) View.MeasureSpec.makeMeasureSpec(dp(44), View.MeasureSpec.EXACTLY)
                else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            resizingHeight = content.measuredHeight
            fit()
            paint()
            root.requestLayout()
            if (attached) panelWindow.attributes = params
        }

        private fun fit(): Boolean {
            val previous = Triple(params.width, params.x, params.y)
            val space = usableSize()
            params.width = minOf(bodyWidth, (space.width - side * 2).coerceAtLeast(1))
            val height = resizingHeight ?: root.height.takeIf { it > 0 } ?: fallbackHeight
            resizeAnchor?.let { anchor ->
                params.x = if (anchor.right) anchor.x - params.width else anchor.x
                params.y = if (anchor.bottom) anchor.y - height else anchor.y + headroom
            }
            val maxX = (space.width - params.width - side).coerceAtLeast(0)
            params.x = params.x.coerceIn(minOf(side, maxX), maxX)
            params.y = params.y.coerceIn(headroom, (space.height - height - shadow.bottomInsetPx).coerceAtLeast(headroom))
            positionX = params.x
            positionY = params.y
            return previous != Triple(params.width, params.x, params.y)
        }

        fun appearanceBounds(): IntArray? {
            if (!attached || dragging || moveQueued || resizeAnchor != null || resizingHeight != null) return null
            val at = IntArray(2)
            root.getLocationOnScreen(at)
            return intArrayOf(at[0] - side, at[1] - headroom,
                at[0] + root.width + side, at[1] + root.height + shadow.bottomInsetPx)
        }

        fun sampleAppearance(sample: JSONObject?, bounds: IntArray?) {
            if (bounds == null || !bounds.contentEquals(appearanceBounds()) || sample?.optBoolean("known") != true) return
            val rgb = sample.optJSONArray("background_rgb") ?: return
            if (rgb.length() != 3) return
            val color = android.graphics.Color.rgb((rgb.optInt(0).coerceIn(0, 255) / 8) * 8,
                (rgb.optInt(1).coerceIn(0, 255) / 8) * 8, (rgb.optInt(2).coerceIn(0, 255) / 8) * 8)
            val luminance = android.graphics.Color.luminance(color)
            val dark = when (sampledDark) { true -> luminance < .36f; false -> luminance < .22f; null -> luminance < .28f }
            sampledDark = dark
            val neutral = if (dark) 0xff191923.toInt() else 0xfffcfbff.toInt()
            val next = SugarGlassMaterial.blend(color, neutral, .86f)
            if (sampledSurface != next) { sampledSurface = next; paint() }
        }

        private fun paint() {
            val saved = SugarOverlayPalette.read(context)
            val surface = sampledSurface ?: saved.surface
            val material = SugarGlassMaterial(surface, sampledDark ?: (android.graphics.Color.luminance(surface) < .36f))
            val ink = if (sampledSurface == null) saved.ink else if (material.dark) 0xfff5f2ff.toInt() else 0xff292735.toInt()
            val tone = material.accent(true)
            val blurred = Build.VERSION.SDK_INT >= 31 && saved.blurDp > 0 && wm.isCrossWindowBlurEnabled
            val style = listOf(surface, ink, saved.opacity, blurred, folded)
            if (lastStyle == style) return
            lastStyle = style
            val fill = material.modalFill(ink, blurred, saved.opacity)
            panelWindow.setBackgroundDrawable(object : GradientDrawable() {
                init {
                    cornerRadius = this@ControlWindow.cornerRadius
                    setColor(fill)
                    setStroke(dp(1).coerceAtLeast(1), SugarGlassMaterial.withAlpha(tone, if (material.dark) .38 else .24))
                }
                override fun getOutline(outline: android.graphics.Outline) {
                    // DecorView queries the blur outline before a replacement drawable has drawn its first frame.
                    val rect = bounds.takeUnless { it.isEmpty }
                    val width = rect?.width() ?: params.width.takeIf { it > 0 } ?: root.width.takeIf { it > 0 } ?: bodyWidth
                    val height = rect?.height() ?: resizingHeight ?: root.height.takeIf { it > 0 } ?: fallbackHeight
                    val radius = cornerRadius.coerceAtMost(minOf(width, height) / 2f)
                    outline.setRoundRect(rect?.left ?: 0, rect?.top ?: 0,
                        (rect?.left ?: 0) + width, (rect?.top ?: 0) + height, radius)
                    // Keep the rounded blur shape, but use our stable cached shadow instead of ROM elevation shadows.
                    outline.alpha = 0f
                }
            })
            panelWindow.decorView.setPadding(0, 0, 0, 0)
            if (Build.VERSION.SDK_INT >= 31) panelWindow.setBackgroundBlurRadius(if (blurred) dp(24) else 0)
            appName.setTextColor(ink)
            compactName.setTextColor(ink)
            collapseGlyph.setInk(ink)
            expandGlyph.setInk(ink)
            val collapseMask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.WHITE)
            }
            collapseButton.background = RippleDrawable(ColorStateList.valueOf(SugarGlassMaterial.withAlpha(tone, .18)), null, collapseMask)
            collapseButton.setPadding(dp(10), dp(10), dp(10), dp(10))
            status.setTextColor(SugarGlassMaterial.withAlpha(ink, .70))
            focusButton.setPalette(ink, tone, material)
            returnButton.setPalette(ink, tone, material)
            actionDivider.setBackgroundColor(SugarGlassMaterial.withAlpha(tone, .21))
            shadow.setPalette(material)
        }

        fun show(next: VirtualTask) {
            target = next
            updatePresentation(collapsed)
            collapseButton.visibility = if (next.controlState == "user" && pendingAction == null) View.VISIBLE else View.INVISIBLE
            if (!iconLoaded || iconPackage != next.controlPackage) {
                iconLoaded = true
                iconPackage = next.controlPackage
                val application = next.controlPackage?.let { runCatching {
                    @Suppress("DEPRECATION")
                    ui.packageManager.getApplicationInfo(it, 0)
                }.getOrNull() }
                loadedName = application?.loadLabel(ui.packageManager)?.toString()
                val drawable = application?.let { runCatching { it.loadIcon(ui.packageManager) }.getOrNull() }
                    ?: MobileControlIcon(MobileIcon.PHONE, context.getColor(R.color.sugar_ink))
                icon.setImageDrawable(drawable)
                compactIcon.setImageDrawable(drawable.constantState?.newDrawable()?.mutate() ?: drawable)
            }
            val name = loadedName ?: context.sugarString(if (next.controlState == "close_failed")
                R.string.mobile_virtual_close_pending else R.string.mobile_virtual_control_app)
            if (appName.text.toString() != name) appName.text = name
            if (compactName.text.toString() != name) compactName.text = name
            capsule.contentDescription = "$name · ${context.sugarString(R.string.mobile_virtual_control_expand)}"
            capsule.tooltipText = context.sugarString(R.string.mobile_virtual_control_expand)
            val caption = context.sugarString(when (next.controlState) {
                "taking_over" -> R.string.mobile_virtual_taking_over
                "restoring" -> R.string.mobile_virtual_restoring
                "focusing" -> R.string.mobile_virtual_focusing
                "close_failed" -> R.string.mobile_virtual_close_failed_short
                "unknown" -> R.string.mobile_virtual_unknown_short
                else -> R.string.mobile_virtual_control_short
            })
            if (status.text.toString() != caption) status.text = caption
            val detail = context.sugarString(when (next.controlState) {
                "close_failed" -> R.string.mobile_virtual_close_failed
                "unknown" -> R.string.mobile_virtual_control_unknown
                else -> R.string.mobile_virtual_user_control
            })
            status.contentDescription = detail
            header.contentDescription = "${appName.text} · ${status.text} · $detail"
            header.tooltipText = context.sugarString(R.string.mobile_virtual_control_drag)
            val closingFailed = next.controlState == "close_failed"
            focusButton.visibility = if (closingFailed) View.GONE else View.VISIBLE
            actionDivider.visibility = if (closingFailed) View.GONE else View.VISIBLE
            focusButton.bind(context.sugarString(if (next.controlAppIsForeground)
                R.string.mobile_virtual_current_app else R.string.mobile_virtual_focus),
                pendingAction == null && next.allowsControlAction(VirtualControlAction.FOCUS), next.controlAppIsForeground)
            returnButton.bind(context.sugarString(if (closingFailed) R.string.mobile_virtual_retry_close else R.string.mobile_virtual_return_short),
                pendingAction == null && next.allowsControlAction(
                    if (closingFailed) VirtualControlAction.RETRY_CLOSE else VirtualControlAction.RESTORE))
            focusButton.setOnClickListener { perform(next, VirtualControlAction.FOCUS) }
            returnButton.setOnClickListener { perform(next,
                if (closingFailed) VirtualControlAction.RETRY_CLOSE else VirtualControlAction.RESTORE) }
            paint()
            val resized = fit()
            if (!dialog.isShowing) {
                panelWindow.attributes = params
                dialog.show()
                panelWindow.attributes = params
                // PhoneWindow parents otherwise clip the illustration and shadow at the card edges.
                var parent: android.view.ViewParent? = root
                while (parent is android.view.ViewGroup) {
                    parent.clipChildren = false
                    parent.clipToPadding = false
                    parent = parent.parent
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    blurListening = true
                    wm.addCrossWindowBlurEnabledListener(ui.mainExecutor, blurChanged)
                }
            } else if (resized) panelWindow.attributes = params
        }

        fun remove() {
            disposed = true
            tracking = false
            dragging = false
            moveQueued = false
            root.removeCallbacks(move)
            if (Build.VERSION.SDK_INT >= 31 && blurListening) wm.removeCrossWindowBlurEnabledListener(blurChanged)
            blurListening = false
            if (dialog.isShowing) runCatching { dialog.dismiss() }
        }
    }

    companion object { val pendingPreview = PendingVirtualPreview() }
}

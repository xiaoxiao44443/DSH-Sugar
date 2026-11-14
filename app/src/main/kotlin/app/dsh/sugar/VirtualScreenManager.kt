package app.dsh.sugar

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.dsh.sugar.runtime.MobileUseTasks
import app.dsh.sugar.runtime.MobileUseTasks.VirtualTask
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.roundToInt

private val VirtualTask.viewable get() = phase == "active" || phase == "parked"

internal fun VirtualTask.currentScreen(inventory: List<VirtualTask>): VirtualTask? = inventory.find {
    it.conversation == conversation && it.taskId == taskId && it.displayId == displayId && it.project == project && it.viewable
}

internal fun VirtualTask?.conversationToOpen(from: String?, live: List<VirtualTask>): String? =
    this?.currentScreen(live)?.conversation?.takeIf { it != from }

/** Activity-owned list and one read-only live preview. Captures always name the owning conversation and lease. */
internal class VirtualScreenManager(
    private val activity: Activity,
    private var ink: Int,
    private val send: (JSONObject) -> Unit,
    private val openConversation: (String, (Boolean) -> Unit) -> Unit,
    private val onDismiss: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sugar-screen-preview").apply { isDaemon = true } }
    private val builder = SugarDialog.builder(activity, landscapeMaxWidthDp = 560)
    private val context = builder.context
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val messageText = TextView(context).apply {
        gravity = Gravity.CENTER
        setPaddingRelative(dp(24), 0, dp(24), 0)
    }
    private val message = ScrollView(context).apply {
        isFillViewport = true
        addView(messageText)
        visibility = View.GONE
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { addView(rows) }
    private val frame = FrameLayout(context).apply {
        addView(image, FrameLayout.LayoutParams(-1,-1))
        addView(scroll, FrameLayout.LayoutParams(-1,-1))
        addView(message, FrameLayout.LayoutParams(-1,-1))
    }
    private val preferences = activity.getSharedPreferences("virtual-preview", Context.MODE_PRIVATE)
    private var orientationKey = ""
    private var fraction = DEFAULT_PREVIEW_FRACTION
    private var range: VirtualPreviewRange? = null
    private var aspect = 9f / 16f
    private val actionSize = dp(PREVIEW_TOOLBAR_WIDTH_DP)
    private val sideBottomInset = dp(PREVIEW_SIDE_BOTTOM_INSET_DP)
    private val heading = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ink)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        isAccessibilityHeading = true
    }
    private val closeButton = iconButton(MobileIcon.CANCEL, R.string.mobile_manager_close) { dismiss() }
    private val header = LinearLayout(context).apply {
        setPaddingRelative(dp(16), 0, 0, 0)
        addView(heading, LinearLayout.LayoutParams(0, -1, 1f))
        addView(closeButton, LinearLayout.LayoutParams(actionSize, -1))
    }
    private val listButton = iconButton(MobileIcon.DISPLAYS, R.string.mobile_virtual_list) { select(null) }
    private val chatButton = iconButton(MobileIcon.CHAT, R.string.mobile_virtual_open_chat) { openSelectedConversation() }
    private val smaller = iconButton(MobileIcon.FOLD, R.string.mobile_preview_smaller) { stepSize(-.16f) }
    private val larger = iconButton(MobileIcon.PLUS, R.string.mobile_preview_larger) { stepSize(.16f) }
    private val grip = ResizeGrip()
    private val actions = listOf(listButton, chatButton, smaller, larger, grip)
    private val toolbar = object : LinearLayout(context) {
        init {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(listButton, LayoutParams(actionSize, -1))
            addView(chatButton, LayoutParams(actionSize, -1))
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(smaller, LayoutParams(actionSize, -1))
            addView(larger, LayoutParams(actionSize, -1))
            addView(grip, LayoutParams(actionSize, -1))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Keep every action reachable even when the window is smaller than five standard targets.
            val count = actions.count { it.visibility != GONE }
            val vertical = orientation == VERTICAL
            val extent = MeasureSpec.getSize(if (vertical) heightMeasureSpec else widthMeasureSpec)
            val slot = minOf(actionSize, extent / count)
            for (action in actions) {
                action.layoutParams.width = if (vertical) -1 else slot
                action.layoutParams.height = if (vertical) slot else -1
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isBaselineAligned = false
        addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(toolbar, LinearLayout.LayoutParams(-1, actionSize))
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(header, LinearLayout.LayoutParams(-1, actionSize))
        addView(body, LinearLayout.LayoutParams(-1, 0))
    }
    private val dialog: AlertDialog = builder.setView(content).create()
    private var targets: List<VirtualTask> = emptyList()
    private var selected: VirtualTask? = null
    private var fromConversation: String? = null
    private var opening = false
    private var watched = emptyList<String>()
    private var watch = ""
    private var titles = emptyMap<String,String>()
    private var titlePaintQueued = false
    private val paintTitles = Runnable {
        titlePaintQueued = false
        if (dialog.isShowing) paint()
    }
    private var generation = 0L
    private var pending: Future<*>? = null
    private var shown: Bitmap? = null
    private val capture = Runnable { captureSelected() }
    private var resizeQueued = false
    private val sizeOnFrame = Runnable {
        resizeQueued = false
        if (dialog.isShowing) sizeFrame()
    }
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleSize() }

    fun show(conversation: String?, task: String?) {
        fromConversation = conversation
        val movePreview = PreviewDrag()
        header.setOnTouchListener(movePreview)
        image.setOnTouchListener(movePreview)
        targets = MobileUseTasks.activeVirtualScreens()
        selected = if (conversation == null && task == null) targets.singleOrNull()?.takeIf { it.viewable }
            else targets.find { it.conversation == conversation && it.taskId == task && it.viewable }
        dialog.setOnDismissListener {
            cancelFrame()
            frame.removeCallbacks(paintTitles)
            frame.removeCallbacks(sizeOnFrame)
            worker.shutdown()
            activity.window.decorView.removeOnLayoutChangeListener(layout)
            dialog.window?.decorView?.removeOnLayoutChangeListener(layout)
            watched = emptyList()
            requestTitles()
            onDismiss()
        }
        dialog.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_BACK || selected == null || targets.size <= 1) false
            else {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) select(null)
                true
            }
        }
        paint()
        dialog.show()
        activity.window.decorView.addOnLayoutChangeListener(layout)
        dialog.window?.decorView?.addOnLayoutChangeListener(layout)
        updateTitleWatch()
        if (selected != null) capture.run()
    }

    private fun sizeFrame() {
        val root = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val preview = selected != null
        val height: Int
        val sideToolbar: Boolean
        if (preview) {
            val space = SugarDialog.previewSpace(activity)
            val key = if ((root.width.takeIf { it > 0 } ?: metrics.widthPixels) >
                (root.height.takeIf { it > 0 } ?: metrics.heightPixels)) "landscape" else "portrait"
            if (orientationKey != key) {
                orientationKey = key
                fraction = preferences.getFloat(key, DEFAULT_PREVIEW_FRACTION).coerceIn(0f, 1f)
            }
            val decor = dialog.window?.decorView
            val platformChrome = if (decor != null && content.height > 0)
                (decor.height - decor.paddingTop - decor.paddingBottom - content.height).coerceAtLeast(0) else 0
            val headerChrome = actionSize + platformChrome
            sideToolbar = key == "landscape" &&
                useSidePreviewToolbar(space.width, space.height, metrics.density, headerChrome, aspect)
            val chrome = headerChrome + if (sideToolbar) sideBottomInset else actionSize
            val bounds = virtualPreviewRange(space.width, space.height, metrics.density, chrome, aspect, sideToolbar)
            range = bounds
            val size = bounds.at(fraction)
            SugarDialog.setPreviewSize(activity, dialog, Size(size.width, size.height + chrome))
            height = size.height
            smaller.isEnabled = fraction > .001f && bounds.small != bounds.large
            larger.isEnabled = fraction < .999f && bounds.small != bounds.large
            smaller.alpha = if (smaller.isEnabled) .85f else .32f
            larger.alpha = if (larger.isEnabled) .85f else .32f
        } else {
            sideToolbar = false
            range = null
            SugarDialog.setPreviewSize(activity, dialog, null)
            val available = root.height.takeIf { it > 0 } ?: metrics.heightPixels
            height = minOf((available * .50f).toInt(), if (targets.isEmpty()) dp(75) else dp(60) * targets.size)
        }
        layOutBody(sideToolbar, preview, height)
    }

    private fun layOutBody(sideToolbar: Boolean, preview: Boolean, frameHeight: Int) {
        val toolDirection = if (sideToolbar) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (toolbar.orientation != toolDirection) {
            content.setPadding(0, 0, 0, if (sideToolbar) sideBottomInset else 0)
            toolbar.orientation = toolDirection
            body.orientation = if (sideToolbar) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            body.layoutDirection = if (sideToolbar) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_INHERIT
            toolbar.layoutParams = if (sideToolbar) LinearLayout.LayoutParams(actionSize, -1)
                else LinearLayout.LayoutParams(-1, actionSize)
            // Inset across the toolbar so icons stay legible even when a slot must be shorter than 48dp.
            val insetX = if (sideToolbar) dp(13) else 0
            val insetY = if (sideToolbar) 0 else dp(13)
            for (button in listOf(listButton, chatButton, smaller, larger))
                button.setPadding(insetX, insetY, insetX, insetY)
        }

        val margin = if (preview) dp(PREVIEW_FRAME_MARGIN_DP) else 0
        val frameParams = frame.layoutParams as LinearLayout.LayoutParams
        val frameWidth = if (sideToolbar) 0 else -1
        if (frameParams.width != frameWidth || frameParams.leftMargin != margin) {
            frameParams.width = frameWidth
            frameParams.height = if (sideToolbar) -1 else 0
            frameParams.setMargins(margin, 0, margin, 0)
            frame.layoutParams = frameParams
        }
        val bodyHeight = frameHeight + if (preview && !sideToolbar) actionSize else 0
        val bodyParams = body.layoutParams
        if (bodyParams.height != bodyHeight) {
            bodyParams.height = bodyHeight
            body.layoutParams = bodyParams
        }
    }

    private fun scheduleSize() {
        if (resizeQueued || !dialog.isShowing) return
        resizeQueued = true
        frame.postOnAnimation(sizeOnFrame)
    }

    private fun stepSize(step: Float) {
        fraction = (fraction + step).coerceIn(0f, 1f)
        rememberSize()
        scheduleSize()
    }

    private fun rememberSize() {
        if (orientationKey.isNotEmpty()) preferences.edit().putFloat(orientationKey, fraction).apply()
    }

    private fun iconButton(icon: MobileIcon, description: Int, action: () -> Unit) = ImageButton(context).apply {
        contentDescription = label(description)
        tooltipText = contentDescription
        setImageDrawable(MobileControlIcon(icon, ink))
        background = touchRipple()
        setPadding(0, dp(13), 0, dp(13))
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun touchRipple() = RippleDrawable(
        ColorStateList.valueOf(Color.argb(32,Color.red(ink),Color.green(ink),Color.blue(ink))), null,
        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
    )

    private inner class PreviewDrag : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var tracking = false
        private var dragging = false
        private var lastX = 0
        private var lastY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (selected == null) return false
            // Local coordinates move along with the dialog or its illustration margins.
            val x = event.rawX.roundToInt()
            val y = event.rawY.roundToInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    dragging = false
                    lastX = x
                    lastY = y
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (tracking) {
                        val dx = x - lastX
                        val dy = y - lastY
                        if (dragging || maxOf(abs(dx), abs(dy)) > slop) {
                            dragging = true
                            SugarDialog.movePreviewBy(activity, dialog, dx, dy)
                            lastX = x
                            lastY = y
                        }
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) tracking = false
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            }
            return true
        }
    }

    private inner class ResizeGrip : View(this@VirtualScreenManager.context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var originX = 0f
        private var originY = 0f
        private var start = 0f
        private var startKey = ""
        private var startRange: VirtualPreviewRange? = null
        private var dragging = false

        init {
            isFocusable = true
            contentDescription = label(R.string.mobile_preview_resize)
            background = touchRipple()
            setOnClickListener {
                fraction = when {
                    fraction < DEFAULT_PREVIEW_FRACTION - .001f -> DEFAULT_PREVIEW_FRACTION
                    fraction < .999f -> 1f
                    else -> 0f
                }
                rememberSize()
                scheduleSize()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            stroke.color = ink
            stroke.alpha = 145
            stroke.strokeWidth = resources.displayMetrics.density * 1.7f
            val x = width * .66f
            val y = height * .66f
            for (n in 1..3) {
                val span = dp(n * 5).toFloat()
                canvas.drawLine(x - span, y, x, y - span, stroke)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRange = range ?: return false
                    originX = event.rawX
                    originY = event.rawY
                    start = fraction
                    startKey = orientationKey
                    dragging = false
                    isPressed = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val bounds = startRange ?: return true
                    val dx = event.rawX - originX
                    val dy = event.rawY - originY
                    if (startKey == orientationKey) {
                        if (!dragging && maxOf(abs(dx), abs(dy)) > slop) dragging = true
                        if (dragging) {
                            fraction = bounds.dragged(start, dx, dy)
                            scheduleSize()
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            if (dragging) rememberSize() else performClick()
                        }
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) endGesture()
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    if (startRange != null && dragging && startKey == orientationKey) {
                        fraction = start
                        scheduleSize()
                    }
                    endGesture()
                }
            }
            return true
        }

        private fun endGesture() {
            startRange = null
            isPressed = false
            parent.requestDisallowInterceptTouchEvent(false)
        }
    }

    fun dismiss() = dialog.dismiss()

    private fun openSelectedConversation() {
        if (opening) return
        val id = selected.conversationToOpen(fromConversation, MobileUseTasks.activeVirtualScreens())
        if (id == null) { refresh(); return }
        opening = true
        val epoch = generation
        chatButton.isEnabled = false
        openConversation(id) { opened ->
            if (dialog.isShowing && generation == epoch) {
                opening = false
                chatButton.isEnabled = true
                if (opened) dismiss()
                else Toast.makeText(activity, label(R.string.mobile_virtual_chat_unavailable), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refresh() {
        if (!dialog.isShowing) return
        val current = MobileUseTasks.activeVirtualScreens()
        if (targets == current) return
        targets = current
        updateTitleWatch()
        val previous = selected
        selected = previous?.currentScreen(targets)
        if (previous != null && selected == null) select(null) else paint()
    }

    private fun updateTitleWatch() {
        val ids = targets.map { it.conversation }.distinct()
        if (ids == watched) return
        watched = ids
        titles = titles.filterKeys { it in ids }
        requestTitles()
    }

    fun requestTitles() {
        watch = UUID.randomUUID().toString()
        send(JSONObject().put("type","mobileTitleWatch").put("watch",watch).put("conversations",JSONArray(watched)))
    }

    fun receiveTitles(request: JSONObject) {
        if (!dialog.isShowing || request.optString("watch") != watch) return
        val id = request.optString("conversation")
        if (id !in watched) return
        val title = (request.opt("title") as? String)?.take(240)?.trim() ?: return
        if (title == titles[id].orEmpty()) return
        titles = if (title.isEmpty()) titles - id else titles + (id to title)
        if (!titlePaintQueued) {
            titlePaintQueued = true
            frame.postOnAnimation(paintTitles)
        }
    }

    private fun label(id: Int, vararg args: Any) = activity.sugarString(id,*args)
    private fun title(task: VirtualTask) = titles[task.conversation]
        ?: label(R.string.mobile_virtual_conversation, task.conversation.takeLast(8))

    private fun paint() {
        val target = selected
        val caption = if (target?.displayId != null) "#${target.displayId}  ·  ${title(target)}"
            else target?.let(::title) ?: label(R.string.mobile_virtual_manager)
        heading.text = caption
        content.accessibilityPaneTitle = caption
        toolbar.visibility = if (target == null) View.GONE else View.VISIBLE
        chatButton.apply {
            visibility = if (target.conversationToOpen(fromConversation, targets) != null) View.VISIBLE else View.GONE
            isEnabled = !opening
        }
        sizeFrame()
        scroll.visibility = if (target == null && targets.isNotEmpty()) View.VISIBLE else View.GONE
        image.visibility = if (target == null) View.GONE else View.VISIBLE
        if (target != null) {
            image.contentDescription = title(target)
            if (shown == null && message.visibility != View.VISIBLE) status(label(R.string.mobile_preview_loading))
            return
        }
        rows.removeAllViews()
        if (targets.isEmpty()) { status(label(R.string.mobile_virtual_empty)); return }
        message.visibility = View.GONE
        for (task in targets) {
            val statusLabel = if (task.phase == "parked") R.string.mobile_virtual_parked else R.string.mobile_virtual_display_id
            val detail = task.displayId?.let { label(statusLabel, it) } ?: label(R.string.mobile_virtual_starting)
            val item = LinearLayout(context)
            item.addView(row(task, detail), LinearLayout.LayoutParams(0, -1, 1f))
            if (task.viewable) item.addView(stopButton(task), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, -1))
            rows.addView(item, LinearLayout.LayoutParams(-1, dp(60)))
        }
    }

    fun updateInk(color: Int) {
        if (ink == color) return
        ink = color
        heading.setTextColor(color)
        messageText.setTextColor(color)
        for (button in listOf(closeButton, listButton, chatButton, smaller, larger)) {
            (button.drawable as MobileControlIcon).setInk(color)
            button.background = touchRipple()
        }
        grip.background = touchRipple()
        grip.invalidate()
        if (dialog.isShowing && selected == null) paint()
    }

    private fun stopButton(task: VirtualTask) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label(R.string.mobile_virtual_stop)
        contentDescription = "$text · ${title(task)}"
        isAllCaps = false
        textSize = 13f
        setTextColor(ink)
        setPaddingRelative(dp(8), 0, dp(14), 0)
        setOnClickListener {
            isEnabled = false
            worker.execute {
                MobileUseTasks.stopVirtualScreen(task.conversation, task.taskId)
                main.post { if (dialog.isShowing) refresh() }
            }
        }
    }

    private fun row(task: VirtualTask, detail: String) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18),0,dp(18),0)
        val enabled = task.viewable
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat() }
        val ripple = Color.argb(30,Color.red(ink),Color.green(ink),Color.blue(ink))
        background = RippleDrawable(ColorStateList.valueOf(ripple),null,mask)
        contentDescription = title(task) + ", " + detail
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(ImageView(context).apply {
            alpha = .62f
            setImageDrawable(MobileControlIcon(MobileIcon.DISPLAYS,ink))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22),dp(22)))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14),0,dp(10),0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(TextView(context).apply {
                text = title(task)
                setTextColor(ink)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = 15f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = detail
                textSize = 12f
                alpha = .64f
                setTextColor(ink)
                setSingleLine()
            })
        }
        addView(labels,LinearLayout.LayoutParams(0,-1,1f))
        if (enabled) addView(TextView(context).apply {
            text = "›"
            textSize = 22f
            alpha = .55f
            setTextColor(ink)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        setOnClickListener { task.currentScreen(MobileUseTasks.activeVirtualScreens())?.let(::select) }
        isEnabled = enabled
        isFocusable = enabled
        if (!enabled) alpha = .65f
    }

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value + .5f).toInt()
    private fun status(text: String) { messageText.text = text; message.visibility = View.VISIBLE }
    private fun select(task: VirtualTask?) {
        cancelFrame()
        selected = task
        aspect = 9f / 16f
        if (task != null) status(label(R.string.mobile_preview_loading))
        paint()
        if (task != null) capture.run()
    }

    private fun cancelFrame() {
        generation++
        opening = false
        main.removeCallbacks(capture)
        // A capture already dispatched to the helper can finish. A generation change discards its result.
        pending?.cancel(false)
        pending = null
        replaceImage(null)
    }

    private fun replaceImage(bitmap: Bitmap?) {
        image.setImageBitmap(bitmap)
        shown?.recycle()
        shown = bitmap
        if (bitmap != null) {
            val next = bitmap.width.toFloat() / bitmap.height
            if (abs(aspect - next) > .001f) {
                aspect = next
                scheduleSize()
            }
        }
    }

    private fun captureSelected() {
        val target = selected ?: return
        val epoch = generation
        pending = worker.submit {
            val bitmap = try {
                val result = MobileUseTasks.preview(target.conversation,target.taskId)
                val data = result.getJSONObject("image").getString("data")
                val bytes = Base64.decode(data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            } catch (_: Exception) { null }
            main.post {
                if (epoch != generation || !dialog.isShowing || target.currentScreen(MobileUseTasks.activeVirtualScreens()) == null) {
                    bitmap?.recycle()
                    if (epoch == generation && dialog.isShowing) refresh()
                    return@post
                }
                replaceImage(bitmap)
                if (bitmap == null) status(label(R.string.mobile_preview_unavailable)) else message.visibility = View.GONE
                main.postDelayed(capture, if (bitmap == null) 1800L else 800L)
            }
        }
    }
}

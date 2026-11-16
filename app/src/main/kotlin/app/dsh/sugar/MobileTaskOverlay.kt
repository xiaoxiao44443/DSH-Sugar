package app.dsh.sugar

import android.animation.ValueAnimator
import android.animation.AnimatorInflater
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import app.dsh.sugar.SugarGlassMaterial.Companion.blend
import app.dsh.sugar.SugarGlassMaterial.Companion.withAlpha as alpha
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.ceil
import kotlin.math.roundToInt

/** An in-Activity control for the conversation's current task. All entry points run on the UI thread. */
internal class MobileTaskOverlay(
    private val activity: Activity,
    private val container: FrameLayout,
    private val onAction: (conversation: String, taskId: String, action: String) -> Unit
) {
    private data class Task(
        val conversation: String, val id: String, val phase: String, val virtual: Boolean,
        val virtualAvailable: Boolean, val running: Boolean?, val language: String
    )

    private val prefs = activity.getSharedPreferences("mobile-task-overlay", Context.MODE_PRIVATE)
    // New selection requests start open. Returning to a previously folded request restores its own choice.
    private val foldByTask = linkedMapOf<String, Boolean>().apply {
        val saved = runCatching { JSONObject(prefs.getString("taskFolds", "{}") ?: "{}") }.getOrNull()
        saved?.keys()?.forEach { id -> put(id, saved.optBoolean(id)) }
    }
    private val bounds = Rect()
    private val location = IntArray(2)
    private var xFraction = prefs.getFloat("x", .96f).coerceIn(0f, 1f)
    private var yFraction = prefs.getFloat("y", .28f).coerceIn(0f, 1f)
    private var folded = false
    private var task: Task? = null
    private var disposed = false
    private var surface = activity.getColor(R.color.sugar_paper)
    private var material = SugarGlassMaterial(surface, Color.luminance(surface) < .36)
    private var ink = activity.getColor(R.color.sugar_ink)
    private var useBlur = activity.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE).getBoolean("blur", true)
    private var opacity = if (useBlur) .24 else .94
    private val isDark get() = material.dark
    private val textInk get() = if (isDark) ink else blend(ink, 0xff28385e.toInt(), .36f)
    private val corner get() = if (folded) 18 else 17
    private val styleUpdates = mutableListOf<() -> Unit>()
    private data class Geometry(val width: Int, val peekWidth: Int, val peekHeight: Int, val overhang: Int,
        val artWidth: Int, val bodyHeight: Int, val fontScale: Float, val artOffset: Int = 0)
    private var geometry: Geometry? = null
    private var controlsDirty = false
    private val root = TaskOverlayLayout(activity).apply {
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
    }
    private val softwareArt = mutableMapOf<Int, Bitmap>()
    private fun picture(resource: Int) = ImageView(activity).apply {
        // Both the hardware window and the mirror's transparent bitmap draw these static assets.
        setImageBitmap(softwareArt.getOrPut(resource) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resources, resource)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        })
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val peek = picture(R.drawable.mobile_mascot_peek).apply {
        scaleType = ImageView.ScaleType.FIT_END
        translationZ = dp(5).toFloat()
    }
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
    }
    private val card = object : ScrollView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = bounds.height() - (geometry?.overhang ?: 0)
            val height = if (cap > 0) MeasureSpec.makeMeasureSpec(
                minOf(cap, MeasureSpec.getSize(heightMeasureSpec)), MeasureSpec.AT_MOST
            ) else heightMeasureSpec
            super.onMeasure(widthMeasureSpec, height)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val r = minOf(dp(corner).toFloat(), width / 2f, height / 2f)
                val x = event.x.coerceIn(r, width - r)
                val y = event.y.coerceIn(r, height - r)
                if (hypot(event.x - x, event.y - y) > r) return false
            }
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        elevation = 0f
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(corner).toFloat())
            }
        }
        clipToOutline = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(content, FrameLayout.LayoutParams(-1, WRAP_CONTENT))
    }
    private val glass = MobileGlassBackdrop(container, card)
    private val mirror = MobileOverlayMirror(container, root, render = root::drawForMirror)
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { if (task != null) layout() }

    init {
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (task != null && (r - l != oldR - oldL || b - t != oldB - oldT)) mirror.changed()
        }
        root.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(peek)
        container.addView(root, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        container.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    fun render(conversation: String, state: JSONObject?) {
        if (disposed) return
        val phase = state?.optString("phase").orEmpty()
        val id = state?.opt("taskId") as? String
        if (state == null || conversation.isBlank() || id.isNullOrBlank() || phase !in livePhases) {
            clear()
            return
        }
        val next = Task(conversation, id, phase, state.optString("screen") == "virtual",
            state.optBoolean("virtualAvailable"), state.opt("running") as? Boolean, SugarLanguage.read(activity))
        val old = task
        if (old == next) return
        if (old == null || old.conversation != conversation || old.id != id)
            folded = foldByTask[id] ?: (phase != "choosing" && prefs.getBoolean("folded", false))
        task = next
        layout(rebuildControls = true)
    }

    fun clear() {
        task = null
        glass.clear()
        mirror.clear()
        content.animate().cancel()
        content.alpha = 1f
        root.visibility = View.GONE
        geometry = null
        styleUpdates.clear()
        content.removeAllViews()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        clear()
        if (container.viewTreeObserver.isAlive) container.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        container.removeView(root)
    }

    fun updateNavigation(visible: List<TaskRailRegion>, touch: List<TaskRailRegion>) {
        root.updateNavigation(visible, touch)
    }

    /** The shell supplies the sampled page color and its contrasting text color. */
    fun updatePalette(surface: String, ink: String, opacity: Double) {
        if (!hex.matches(surface) || !hex.matches(ink) || opacity !in 0.0..1.0) return
        val nextSurface = Color.parseColor(surface)
        val nextInk = Color.parseColor(ink)
        val blurred = activity.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE).getBoolean("blur", true)
        val alpha = if (blurred) (.17 + opacity * .075) else .94
        if (nextSurface == this.surface && nextInk == this.ink && alpha == this.opacity && blurred == useBlur) return
        useBlur = blurred
        this.surface = nextSurface
        material = SugarGlassMaterial(nextSurface, Color.luminance(nextSurface) < .36)
        this.ink = nextInk
        this.opacity = alpha
        applyStyles()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
    private fun shape(fill: Int, radius: Int, edge: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (edge != null) setStroke(dp(1).coerceAtLeast(1), edge)
    }
    private fun applyStyles() {
        // Keep white pages neutral and let the sampled page show through the thin glass tint.
        val base = when {
            !folded -> opacity
            useBlur -> opacity * .68
            else -> opacity * .50
        }
        val fill = alpha(material.tint, base)
        card.background = if (folded) GradientDrawable().apply {
            cornerRadius = dp(corner).toFloat()
            setColor(fill)
            setStroke(1, material.badgeEdge)
        } else SugarGlassFrame(material, fill, dp(corner).toFloat(), activity.resources.displayMetrics.density)
        card.invalidateOutline()
        styleUpdates.forEach { it() }
        if (task != null) {
            alignGlass()
            mirror.changed()
        }
    }

    private fun rebuild() {
        val state = task ?: return
        val size = geometry ?: return
        val words = when (state.language) { "ja" -> ja; "en" -> en; else -> zh }
        val status = when (state.phase) {
            "choosing" -> words.choose
            "disabled" -> if (state.virtual) words.virtualOff else words.mainOff
            "permission" -> if (state.virtual) words.adbRequired else words.serviceRequired
            "busy" -> words.busy
            "starting" -> words.starting
            "parked" -> words.parked
            "running" -> words.running
            "active" -> if (state.running == true) words.running else words.ready
            else -> words.ready
        }
        val title = if (state.phase == "choosing") words.phone else if (state.virtual) words.virtual else words.main
        content.removeAllViews()
        styleUpdates.clear()
        card.scrollTo(0, 0)
        peek.visibility = if (folded) View.GONE else View.VISIBLE
        if (folded) {
            val badge = FrameLayout(activity).apply { contentDescription = "${words.expand} · $title · $status" }
            val portrait = picture(R.drawable.mobile_mascot_badge)
            badge.addView(portrait, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                setMargins(dp(5), dp(4), dp(5), dp(4))
            })
            val dot = View(activity)
            badge.addView(dot, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(5)
                bottomMargin = dp(5)
            })
            styleUpdates += {
                val color = if (state.phase in setOf("active","running","ready","parked")) material.accent(state.virtual)
                    else if (isDark) 0xffffce86.toInt() else 0xffb77c31.toInt()
                dot.background = shape(color, 8, blend(surface, Color.WHITE, .78f))
            }
            content.addView(badge, LinearLayout.LayoutParams(MATCH_PARENT, size.width))
            asHandle(badge)
        } else {
            val header = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, dp(4), 0)
            }
            val label = TextView(activity).apply {
                text = title
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "$title · ${words.collapse}"
            }
            styleUpdates += {
                label.setTextColor(textInk)
                label.compoundDrawablePadding = dp(8)
                val icon = MobileControlIcon(if (state.virtual) MobileIcon.DISPLAYS else MobileIcon.PHONE, material.accent(state.virtual))
                icon.setBounds(0, 0, dp(19), dp(19))
                label.setCompoundDrawablesRelative(icon, null, null, null)
            }
            asHandle(label)
            header.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val fold = ImageView(activity).apply { contentDescription = words.collapse }
            styleUpdates += {
                fold.background = RippleDrawable(ColorStateList.valueOf(alpha(ink,.12)), null, shape(Color.WHITE, 12))
                fold.setImageDrawable(MobileControlIcon(MobileIcon.FOLD, material.accent()))
                fold.setPadding(dp(14),dp(14),dp(14),dp(14))
            }
            asHandle(fold)
            header.addView(fold, LinearLayout.LayoutParams(dp(48), dp(48)))
            content.addView(header)
            val divider = View(activity).apply { setBackgroundColor(Color.WHITE) }
            content.addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, 1))

            val body = FrameLayout(activity).apply {
                minimumHeight = size.bodyHeight
                clipChildren = false
            }
            val mascot = picture(R.drawable.mobile_mascot_phone).apply {
                scaleType = ImageView.ScaleType.FIT_END
                // The faded hem can extend into the footer's padding. The button paints above it.
                translationY = size.artOffset.toFloat()
            }
            body.addView(mascot, FrameLayout.LayoutParams(size.artWidth, size.bodyHeight, Gravity.START or Gravity.BOTTOM).apply {
                marginStart = dp(3)
            })
            val buttonInset = dp(11)
            val controls = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0,dp(5),0,dp(5))
            }
            body.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply {
                marginStart = size.artWidth + dp(7)
                marginEnd = buttonInset
            })
            val detail = TextView(activity).apply {
                text = status
                textSize = if (state.phase == "choosing") 15f else 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(dp(2),dp(3),0,dp(5))
            }
            styleUpdates += { detail.setTextColor(textInk) }
            controls.addView(detail)
            val actions = LinearLayout(activity).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                clipChildren = false
            }
            controls.addView(actions)
            val chips = mutableListOf<Pair<Button, Int>>()
            val choosing = state.phase == "choosing"
            val modeMinimum = if (state.language == "zh") 82 else 78
            fun natural(button: Button, label: String, minimum: Int, padding: Int): Int {
                val textWidth = ceil(button.paint.measureText("\u2002$label")).toInt()
                return maxOf(dp(minimum), dp(16) + textWidth + dp(padding) * 2)
            }
            fun add(label: String, action: String, icon: MobileIcon, enabled: Boolean = true) {
                val button = button(state, label, action, icon, enabled)
                chips += button to natural(button, label, modeMinimum,
                    if (action == "preview") 4 else 6)
            }
            when (state.phase) {
                "choosing" -> {
                    add(words.main,"select",MobileIcon.PREVIEW)
                    add(words.virtual,"selectVirtual",MobileIcon.DISPLAYS,state.virtualAvailable)
                }
                "disabled" -> add(words.settings, if (state.virtual) "virtualSettings" else "mainSettings", MobileIcon.SETTINGS)
                "permission" -> add(words.connect, if (state.virtual) "adb" else "mainSettings", MobileIcon.SETTINGS)
                "active","running","ready","parked" -> if (state.virtual) add(words.preview,"preview",MobileIcon.PREVIEW)
            }
            val available = (size.width - size.artWidth - dp(7) - buttonInset).coerceAtLeast(1)
            val gap = dp(11)
            // A single action uses the last screen-choice column, flush with the footer.
            val useEndColumn = chips.size == 1
            val reference = if (useEndColumn) listOf(words.main,words.virtual).map {
                natural(chips.single().first, it, modeMinimum, 6)
            } else chips.map { it.second }
            val widest = reference.maxOrNull() ?: 0
            val stacked = reference.size == 2 && reference.sum() + gap > available
            val sameWidth = stacked || widest * 2 + gap <= available
            val widths = reference.map { minOf(available, if (sameWidth) widest else it) }
            actions.orientation = if (stacked && !useEndColumn) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            chips.forEachIndexed { index, (button, naturalWidth) ->
                val width = if (useEndColumn) maxOf(widths.last(),naturalWidth).coerceAtMost(available)
                    else widths[index]
                actions.addView(button, LinearLayout.LayoutParams(width, WRAP_CONTENT).apply {
                    if (index > 0 && !stacked) marginStart = gap
                })
            }
            content.addView(body)
            val footer = button(state, if (choosing) words.cancel else if (state.phase == "parked") activity.sugarString(R.string.mobile_virtual_stop) else words.stop, "stop", null)
            content.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = buttonInset
                marginEnd = buttonInset
                bottomMargin = dp(4)
            })
        }
        applyStyles()
    }

    /** A small alpha-only outer shadow works on both the View canvas and the transparent card mirror. */
    private inner class ChipShadow {
        private val density = activity.resources.displayMetrics.density
        private val pad = dp(4)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var mask: Bitmap? = null

        fun draw(canvas: Canvas, width: Int, height: Int, tint: Int) {
            val w = width + pad * 2
            val h = height + pad * 2
            if (width <= 0 || height <= dp(10)) return
            val image = mask?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { bitmap ->
                    bitmap.density = Bitmap.DENSITY_NONE
                    val surface = Canvas(bitmap)
                    val left = pad.toFloat()
                    val top = left + dp(5)
                    val right = left + width
                    val bottom = left + height - dp(5)
                    val radius = dp(10).toFloat()
                    val offset = .85f * density
                    val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        maskFilter = BlurMaskFilter(1.9f * density, BlurMaskFilter.Blur.NORMAL)
                    }
                    surface.drawRoundRect(left,top + offset,right,bottom + offset,radius,radius,pen)
                    pen.maskFilter = null
                    pen.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    surface.drawRoundRect(left,top,right,bottom,radius,radius,pen)
                    mask = bitmap
                }
            ink.color = tint
            canvas.drawBitmap(image,-pad.toFloat(),-pad.toFloat(),ink)
        }
    }

    private fun button(state: Task, title: String, action: String, icon: MobileIcon?, enabled: Boolean = true): Button {
        val footer = icon == null
        val shadow = if (footer) null else ChipShadow()
        val button = object : Button(activity) {
            override fun draw(canvas: Canvas) {
                shadow?.draw(canvas, width, height, alpha(material.accent(icon == MobileIcon.DISPLAYS || state.virtual),
                    if (enabled) .085 else .04))
                super.draw(canvas)
            }
            override fun setPressed(pressed: Boolean) {
                val changed = pressed != isPressed
                super.setPressed(pressed)
                if (changed && task == state) mirror.changed()
            }
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.text = title
            }
        }.apply {
            text = title
            isAllCaps = false
            isEnabled = enabled
            textSize = if (footer) 14f else 12.5f
            typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
            letterSpacing = 0f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            backgroundTintList = null
            stateListAnimator = if (motionEnabled())
                AnimatorInflater.loadStateListAnimator(activity, R.animator.mobile_control_press) else null
            setOnClickListener { if (task == state && isEnabled) onAction(state.conversation,state.id,action) }
        }
        styleUpdates += {
            val tone = material.accent(icon == MobileIcon.DISPLAYS || state.virtual)
            if (icon != null) {
                val glyph = MobileControlIcon(icon, alpha(tone, if (enabled) 1.0 else .45))
                glyph.setBounds(0,0,dp(16),dp(16))
                // Center the run horizontally and align the icon to the label's visible ink vertically.
                button.text = SpannableString("\uFFFC\u2002$title").apply {
                    setSpan(LabelIconSpan(glyph, title, button.paint), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            button.setTextColor(alpha(ink, if (enabled) .98 else .47))
            val chip = GradientDrawable().apply {
                cornerRadius = dp(if (footer) 24 else 10).toFloat()
                setColor(material.buttonFill(tone, emphasized = footer))
                setStroke(1, alpha(tone, if (isDark) .16 else .12))
            }
            button.background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(alpha(tone, .19)), chip, null),
                0,dp(if (footer) 6 else 5),0,dp(if (footer) 6 else 5))
            val horizontal = dp(if (footer) 10 else if (action == "preview") 4 else 6)
            button.setPadding(horizontal, dp(4), horizontal, dp(4))
        }
        return button
    }

    private class LabelIconSpan(private val icon: MobileControlIcon, title: String, paint: Paint) : ReplacementSpan() {
        private val iconTop: Int
        init {
            val ink = Rect()
            paint.getTextBounds(title, 0, title.length, ink)
            iconTop = (ink.exactCenterY() - icon.bounds.exactCenterY()).roundToInt()
        }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                paint.getFontMetricsInt(fm)
                fm.ascent = minOf(fm.ascent, iconTop)
                fm.descent = maxOf(fm.descent, iconTop + icon.bounds.height())
                fm.top = minOf(fm.top, fm.ascent)
                fm.bottom = maxOf(fm.bottom, fm.descent)
            }
            return icon.bounds.width()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint) {
            val save = canvas.save()
            canvas.translate(x, (y + iconTop).toFloat())
            icon.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun toggle() {
        val id = task?.id ?: return
        folded = !folded
        foldByTask.remove(id)
        foldByTask[id] = folded
        if (foldByTask.size > 64) foldByTask.remove(foldByTask.keys.first())
        prefs.edit().putBoolean("folded", folded).putString("taskFolds", JSONObject(foldByTask.toMap()).toString()).apply()
        layout(rebuildControls = true)
        content.animate().cancel()
        if (motionEnabled()) {
            content.alpha = .64f
            content.animate().alpha(1f).setDuration(110L).setInterpolator(DecelerateInterpolator())
                .withEndAction { if (task?.id == id) mirror.changed() }.start()
        } else content.alpha = 1f
    }

    private fun motionEnabled() = ValueAnimator.areAnimatorsEnabled() &&
        !activity.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE).getBoolean("reducedMotion", false)

    private fun asHandle(view: View) {
        view.isFocusable = true
        view.setOnClickListener { toggle() }
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var originX = 0f
        var originY = 0f
        var dragging = false
        var cancelled = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    originX = root.x; originY = root.y
                    dragging = false; cancelled = false
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> cancelled = true
                MotionEvent.ACTION_MOVE -> if (!cancelled) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        val freeX = bounds.width() - root.width
                        val freeY = bounds.height() - root.height
                        if (freeX > 0) xFraction = ((originX + dx - bounds.left) / freeX).coerceIn(0f, 1f)
                        if (freeY > 0) yFraction = ((originY + dy - bounds.top) / freeY).coerceIn(0f, 1f)
                        place()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    if (dragging) prefs.edit().putFloat("x", xFraction).putFloat("y", yFraction).apply()
                    else if (!cancelled && event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun layout(rebuildControls: Boolean = false) {
        if (rebuildControls) controlsDirty = true
        if (container.width < 1 || container.height < 1) return
        val window = container.rootView
        val insets = container.rootWindowInsets
        val safe = if (Build.VERSION.SDK_INT >= 30) insets?.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()) else null
        val left = safe?.left ?: maxOf(insets?.systemWindowInsetLeft ?: 0, insets?.displayCutout?.safeInsetLeft ?: 0)
        val top = safe?.top ?: maxOf(insets?.systemWindowInsetTop ?: 0, insets?.displayCutout?.safeInsetTop ?: 0)
        val right = safe?.right ?: maxOf(insets?.systemWindowInsetRight ?: 0, insets?.displayCutout?.safeInsetRight ?: 0)
        val bottom = safe?.bottom ?: maxOf(insets?.systemWindowInsetBottom ?: 0, insets?.displayCutout?.safeInsetBottom ?: 0)
        container.getLocationInWindow(location)
        val previousHeight = bounds.height()
        val gap = dp(8)
        bounds.set(maxOf(container.paddingLeft, left - location[0]) + gap,
            maxOf(container.paddingTop, top - location[1]) + gap,
            minOf(container.width - container.paddingRight, window.width - right - location[0]) - gap,
            minOf(container.height - container.paddingBottom, window.height - bottom - location[1]) - gap)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            root.visibility = View.INVISIBLE
            glass.clear()
            mirror.clear()
            return
        }
        val size = measureGeometry()
        val resized = geometry != size
        if (resized) {
            geometry = size
            root.layoutParams = root.layoutParams.apply { width = size.width }
            card.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = size.overhang }
            peek.layoutParams = FrameLayout.LayoutParams(size.peekWidth,size.peekHeight, Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(8)
            }
        }
        if (resized || controlsDirty) {
            rebuild()
            controlsDirty = false
        }
        if (previousHeight != bounds.height()) card.requestLayout()
        root.visibility = View.VISIBLE
        place()
    }

    private fun measureGeometry(): Geometry {
        val fontScale = activity.resources.configuration.fontScale
        if (folded) return Geometry(minOf(dp(56), bounds.width(), bounds.height()),0,0,0,0,0,fontScale)
        val width = minOf(bounds.width(), dp(316))
        val compact = bounds.height() < dp(265)
        // Reserve the controls first when landscape or the IME leaves little height.
        val art = minOf((width * .29f).roundToInt(),dp(if (compact) 74 else 92))
        val bodyHeight = dp(if (compact) 84 else 119)
        val peekHeight = minOf((width * .245f).roundToInt(), dp(if (compact) 45 else 76),
            (bounds.height() - dp(128)).coerceAtLeast(0))
        val overhang = (peekHeight - dp(6)).coerceAtLeast(0)
        val artOffset = dp(if (compact) 4 else if (bounds.height() < bounds.width()) 6 else 8)
        return Geometry(width,(peekHeight * 1.69f).roundToInt(),peekHeight,overhang,art,bodyHeight,fontScale,artOffset)
    }

    private fun place() {
        val x = bounds.left + (bounds.width() - root.width).coerceAtLeast(0) * xFraction
        val y = bounds.top + (bounds.height() - root.height).coerceAtLeast(0) * yFraction
        if (x != root.x || y != root.y) {
            root.x = x
            root.y = y
            root.moved()
        }
        alignGlass()
        mirror.place()
    }

    private fun alignGlass() = glass.align(dp(corner), if (useBlur) dp(if (folded) 20 else 13) else 0, isDark)

    private data class Words(val phone: String, val main: String, val virtual: String, val choose: String,
        val mainOff: String, val virtualOff: String, val adbRequired: String, val serviceRequired: String,
        val busy: String, val starting: String, val ready: String, val running: String, val settings: String,
        val connect: String, val accessibility: String, val preview: String, val cancel: String, val stop: String,
        val collapse: String, val expand: String, val parked: String)

    private companion object {
        val livePhases = setOf("choosing", "disabled", "permission", "busy", "starting", "active", "ready", "running", "parked")
        val hex = Regex("#[0-9a-fA-F]{6}")
        val zh = Words("手机操作", "主屏", "虚拟屏", "在哪块屏幕上操作？", "主屏操作已关闭", "虚拟屏操作已关闭",
            "等待连接 Root 或无线 ADB", "等待无障碍或 Root 授权", "另一项任务正在使用此屏幕", "正在连接…", "已连接，就绪", "操作进行中",
            "操作设置", "连接设置", "无障碍设置", "查看画面", "取消", "停止", "收起手机操作", "展开手机操作", "本轮已完成，画面已保留")
        val en = Words("Phone control", "Main screen", "Virtual screen", "Which screen should the agent use?",
            "Main screen control is off", "Virtual screen control is off", "Waiting for Root or wireless ADB", "Waiting for accessibility or Root access",
            "Another task is using this screen", "Connecting…", "Connected and ready", "Task in progress",
            "Settings", "Connection settings", "Accessibility", "Preview", "Cancel", "Stop", "Collapse phone controls", "Expand phone controls", "Turn complete · screen kept open")
        val ja = Words("スマートフォン操作", "メイン画面", "仮想画面", "どの画面を操作しますか？", "メイン画面の操作はオフです",
            "仮想画面の操作はオフです", "Root または ADB の接続待ち", "ユーザー補助または Root の許可待ち",
            "別のタスクがこの画面を使用中です", "接続中…", "接続完了・準備完了", "操作中", "操作設定", "接続設定",
            "ユーザー補助", "プレビュー", "キャンセル", "停止", "操作パネルを折りたたむ", "操作パネルを開く", "操作が完了しました。画面は開いたままです")
    }
}

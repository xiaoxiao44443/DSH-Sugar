package app.dsh.sugar

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.graphics.Insets
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.roundToInt

/** Recovery must keep its navigation and repair actions available when Chromium cannot start. */
internal class RecoveryNavigation(
    private val activity: Activity, private val content: View, settings: Bundle?, private val navigate: (String) -> Unit,
    private val session: Boolean = false
) : FrameLayout(activity) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val preferences = activity.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE)
    private val railWidth = dp(62)
    private var webInsets: Insets = Insets.NONE
    var onSessionDockChanged: ((Int, Int) -> Unit)? = null
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    var left = preferences.getString("side", "right") == "left"
        private set
    private var expanded = false
    private var webRail = false
    private var fraction = 0f
    private var animator: ValueAnimator? = null
    private val panel = LinearLayout(activity)
    private val grip = FrameLayout(activity)
    private val mark = View(activity)
    private val historyButtons = mutableMapOf<String,ImageButton>()
    private val clock = TextClock(activity).apply {
        // The Web rail uses local hours 00–23 and two-digit minutes, including on 12-hour devices.
        format12Hour = "HH:mm"
        format24Hour = "HH:mm"
        gravity = Gravity.CENTER
        isSingleLine = true
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        letterSpacing = -.055f
        rotation = 90f
        setTextColor(activity.getColor(R.color.sugar_ink))
    }
    private val clockFrame = FrameLayout(activity)
    private var startX = 0f
    private var startY = 0f
    private var edge: Boolean? = null
    private var startFraction = 0f
    private var dragging = false

    init {
        addView(if (session) content else ScrollView(activity).apply { addView(content) })
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.setPadding(0,dp(6),0,dp(8))
        val paper = activity.getColor(R.color.sugar_paper)
        val ink = activity.getColor(R.color.sugar_ink)
        panel.setBackgroundColor(SugarGlassMaterial.withAlpha(paper,.96))
        panel.elevation = dp(4).toFloat()
        panel.isClickable = true
        addView(panel, LayoutParams(railWidth, -1))
        fun button(label: String, icon: Int, click: () -> Unit) =
            ImageButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
                contentDescription = label
                tooltipText = label
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { click() }
            }
        val cap = button(activity.sugarString(R.string.diag_nav_close), R.mipmap.ic_launcher) { settle(false) }
        cap.scaleType = ImageView.ScaleType.FIT_CENTER
        cap.setPadding(dp(12),dp(9),dp(12),dp(9))
        panel.addView(cap, LinearLayout.LayoutParams(dp(52),dp(48)))
        clockFrame.addView(clock, LayoutParams(dp(106),dp(42),Gravity.CENTER))
        panel.addView(clockFrame, LinearLayout.LayoutParams(-1,dp(116)))
        val scroll = ScrollView(activity).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val contextItems = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        scroll.addView(contextItems)
        panel.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        for ((route, icon) in settingsIcons) {
            val label = settings?.getString(route) ?: continue
            contextItems.addView(button(label, icon) { navigate(route) }, LinearLayout.LayoutParams(-1,dp(48)))
        }
        fun route(id: String, label: Int, icon: Int, group: LinearLayout) {
            val action = button(activity.sugarString(label),icon) { navigate(id) }
            if (id == "back" || id == "forward") historyButtons[id] = action
            group.addView(action,LinearLayout.LayoutParams(-1,dp(48)))
        }
        if (session) {
            // All four remain reachable by scrolling in a very short window.
            route("home", R.string.diag_session_home, R.drawable.recovery_nav_home, contextItems)
            route("forward", R.string.diag_forward, R.drawable.recovery_nav_forward, contextItems)
            route("back", R.string.diag_back, R.drawable.recovery_nav_back, contextItems)
            route("end", R.string.diag_end, R.drawable.recovery_nav_end, contextItems)
            setHistory(false,false)
        } else {
            route("home", R.string.diag_nav_home, R.drawable.recovery_nav_home, panel)
            route("back", R.string.diag_back, R.drawable.recovery_nav_back, panel)
        }

        mark.background = GradientDrawable().apply {
            setColor(ink)
            cornerRadius = dp(5).toFloat()
        }
        grip.addView(mark, LayoutParams(dp(8),dp(44),Gravity.CENTER))
        grip.isFocusable = true
        grip.setOnClickListener { settle(!expanded) }
        grip.elevation = dp(5).toFloat()
        addView(grip, LayoutParams(dp(22),dp(144),Gravity.CENTER_VERTICAL))
        setSide(left)
        settle(false, animate = false)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDialogDock()
            val strip = minOf(dp(32), width / 2)
            val zone = if (preferences.getBoolean("fullEdgeGestures",false)) height else minOf(height,dp(200))
            val y = (height - zone) / 2
            systemGestureExclusionRects = listOf(Rect(0,y,strip,y+zone),Rect(width-strip,y,width,y+zone))
            cap.visibility = if (height >= dp(290)) VISIBLE else GONE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The outer frame has already removed bars and the cutout. Settings computes its
        // centered maximum width and 5vw gutter against the whole viewport before adding those insets.
        val outer = parent as View
        val viewportWidth = MeasureSpec.getSize(widthMeasureSpec) + outer.paddingLeft + outer.paddingRight
        val viewportHeight = MeasureSpec.getSize(heightMeasureSpec) + (if (session) 0 else outer.paddingTop + outer.paddingBottom)
        clockFrame.visibility = if (viewportHeight <= dp(470) ||
            viewportWidth > viewportHeight && viewportHeight <= dp(550)) GONE else VISIBLE
        if (clockFrame.visibility == VISIBLE) {
            val available = MeasureSpec.getSize(heightMeasureSpec) - dp(14) - webInsets.top - webInsets.bottom
            val size = if (viewportHeight <= dp(650)) (available * .047f).coerceIn(dp(23).toFloat(),dp(26).toFloat())
                else minOf((railWidth - dp(1)) * .63f, (available * .049f).coerceIn(dp(30).toFloat(),dp(42).toFloat()))
            if (clock.textSize != size) clock.setTextSize(TypedValue.COMPLEX_UNIT_PX,size)
            val length = ceil(clock.paint.measureText("00:00")).toInt() + dp(3)
            clock.layoutParams.width = length
            clock.layoutParams.height = ceil(size * 1.17f).toInt()
            clockFrame.layoutParams.height = length + dp(11)
        }
        if (session) {
            super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            return
        }
        val wide = viewportWidth > viewportHeight && viewportWidth >= dp(580)
        val pageMargin = (viewportWidth - dp(if (wide) 1040 else 660)).coerceAtLeast(0) / 2
        val gutter = (viewportWidth * .05f).roundToInt().coerceIn(dp(17),dp(32))
        val dockGutter = dp(72)
        content.setPadding(pageMargin + (if (left) dockGutter else gutter), content.paddingTop,
            pageMargin + (if (left) gutter else dockGutter), content.paddingBottom)
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }

    private fun updateDialogDock() {
        if (webRail) return
        val outer = parent as? View
        val inset = if (session) (if (left) webInsets.left else webInsets.right)
            else if (left) outer?.paddingLeft else outer?.paddingRight
        SugarDialog.updateDock(activity,left,if (expanded) railWidth + (inset ?: 0) else 0)
        onSessionDockChanged?.invoke(if (expanded && left) railWidth else 0,if (expanded && !left) railWidth else 0)
    }

    fun showWebRail(visible: Boolean, side: String) {
        webRail = visible
        settle(false,animate = false)
        if (visible) {
            onSessionDockChanged?.invoke(0,0)
            SugarDialog.updateDock(activity,false,0)
        } else setSide(side == "left")
        grip.visibility = if (visible) GONE else VISIBLE
    }

    fun setSessionInsets(safe: Insets) {
        if (webInsets == safe) return
        webInsets = safe
        for (control in listOf(panel,grip)) {
            val box = control.layoutParams as LayoutParams
            box.setMargins(safe.left,safe.top,safe.right,safe.bottom)
            control.layoutParams = box
        }
        updateDialogDock()
    }

    private fun setSide(onLeft: Boolean) {
        left = onLeft
        val side = if (left) Gravity.LEFT else Gravity.RIGHT
        (panel.layoutParams as LayoutParams).also { it.gravity = side; panel.layoutParams = it }
        (grip.layoutParams as LayoutParams).also { it.gravity = side or Gravity.CENTER_VERTICAL; grip.layoutParams = it }
        updateDialogDock()
    }

    private fun position(value: Float) {
        fraction = value
        val inward = if (left) railWidth else -railWidth
        panel.translationX = inward * (value - 1f)
        // Sugar's expanded grip sits inside the rail along its inner edge.
        val gripTravel = railWidth - grip.layoutParams.width
        grip.translationX = (if (left) gripTravel else -gripTravel) * value
        mark.alpha = .18f + .62f * value
        panel.visibility = if (value > 0f) VISIBLE else INVISIBLE
    }

    private fun settle(open: Boolean, animate: Boolean = true) {
        animator?.cancel()
        expanded = open
        if (session) requestLayout()
        updateDialogDock()
        grip.contentDescription = context.sugarString(if (open) R.string.diag_nav_close else R.string.diag_nav_open)
        panel.importantForAccessibility = if (open) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val target = if (open) 1f else 0f
        if (!animate || preferences.getBoolean("reducedMotion",false) || fraction == target) position(target)
        else animator = ValueAnimator.ofFloat(fraction,target).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { position(it.animatedValue as Float) }
            start()
        }
    }

    fun back(side: String?) {
        if (side != null) {
            if (!expanded) { setSide(side == "left"); settle(true) }
        } else if (expanded) settle(false) else navigate("back")
    }

    fun setHistory(canBack: Boolean, canForward: Boolean) {
        for ((id, button) in historyButtons) {
            button.isEnabled = if (id == "back") canBack else canForward
            button.alpha = if (button.isEnabled) 1f else .38f
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (webRail) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                dragging = false
                val zone = dp(32)
                val onPanel = expanded && (if (left) startX <= webInsets.left + railWidth else startX >= width - webInsets.right - railWidth)
                edge = when {
                    onPanel -> left
                    startX <= webInsets.left + zone -> true
                    startX >= width - webInsets.right - zone -> false
                    else -> null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val side = edge ?: return false
                val dx = event.x - startX
                val dy = abs(event.y - startY)
                if (dy > slop && dy >= abs(dx)) edge = null
                else if (abs(dx) > slop && abs(dx) > 1.35f * dy) {
                    animator?.cancel()
                    startFraction = if (left == side) fraction else 0f
                    setSide(side)
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> edge = null
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (webRail) return super.onTouchEvent(event)
        if (!dragging) return super.onTouchEvent(event)
        val inward = (event.x - startX) * (if (left) 1f else -1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> position((startFraction + inward / railWidth).coerceIn(0f,1f))
            MotionEvent.ACTION_UP -> {
                val open = when {
                    inward > dp(20) -> true
                    inward < -dp(20) -> false
                    else -> startFraction >= .5f
                }
                settle(open)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { settle(expanded); dragging = false }
        }
        return true
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    companion object {
        val settingsIcons = mapOf(
            "overview" to R.drawable.recovery_nav_overview, "appearance" to R.drawable.recovery_nav_appearance,
            "gestures" to R.drawable.recovery_nav_gestures, "splash" to R.drawable.recovery_nav_splash,
            "pet" to R.drawable.recovery_nav_pet, "adb" to R.drawable.recovery_nav_adb
        )
    }
}

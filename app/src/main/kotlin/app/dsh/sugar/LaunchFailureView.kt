package app.dsh.sugar

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Native error UI is available even when Chromium itself cannot initialize. */
internal class LaunchFailureView(activity: Activity, retry: () -> Unit, paper: Int, ink: Int) : LinearLayout(activity) {
    private val artwork = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val message = TextView(activity).apply {
        textSize = 16f
        setTextColor(ink)
        gravity = Gravity.CENTER
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val controls = ScrollView(activity)
    private val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER }
    private var showing = false
    private var foreground = true
    private var animation: AnimatedImageDrawable? = null
    private var loadId = 0

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(paper)
        addView(artwork, LayoutParams(dp(180),dp(180)))
        val column = LinearLayout(activity).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        column.addView(message, LayoutParams(-1,-2))
        val accent = SugarGlassMaterial(paper,ink == Color.WHITE).accent()
        fun readable(color: Int): Int {
            val a = Color.luminance(paper) + .05f
            val b = Color.luminance(color) + .05f
            return if (maxOf(a,b) / minOf(a,b) >= 4.5f) color else ink
        }
        fun button(label: Int, icon: Int, color: Int, primary: Boolean, action: () -> Unit) =
            Button(activity,null,android.R.attr.borderlessButtonStyle).apply {
                text = activity.sugarString(label)
                isAllCaps = false
                setSingleLine(false)
                ellipsize = null
                textSize = 15f
                typeface = Typeface.create(if (primary) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
                letterSpacing = 0f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(color)
                minHeight = dp(48)
                minimumHeight = dp(48)
                minWidth = dp(48)
                minimumWidth = dp(48)
                setPaddingRelative(dp(10),dp(9),dp(10),dp(9))
                val symbol = activity.getDrawable(icon)!!.mutate()
                symbol.setTint(color)
                symbol.setBounds(0,0,dp(20),dp(20))
                setCompoundDrawablesRelative(symbol,null,null,null)
                compoundDrawablePadding = dp(7)
                val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
                backgroundTintList = null
                stateListAnimator = null
                background = RippleDrawable(ColorStateList.valueOf(SugarGlassMaterial.withAlpha(color,.14)),null,mask)
                setOnClickListener { action() }
            }
        actions.addView(button(R.string.sugar_retry,R.drawable.ic_launch_reconnect,readable(accent),true,retry),
            LayoutParams(-2,-2))
        val secondary = readable(SugarGlassMaterial.blend(paper,ink,.64f))
        actions.addView(button(R.string.diag_title,R.drawable.ic_recovery_flask,secondary,false) {
            DiagnosticsRecoveryActivity.open(activity)
        }, LayoutParams(-2,-2))
        column.addView(actions, LayoutParams(-1,-2).apply { topMargin = dp(12) })
        controls.addView(column)
        addView(controls, LayoutParams(-1,-2))
    }

    fun describe(value: String) { if (message.text.toString() != value) message.text = value }
    fun setForegroundActive(active: Boolean) { foreground = active; animateIfVisible() }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        val height = (MeasureSpec.getSize(heightSpec) - paddingTop - paddingBottom).coerceAtLeast(1)
        val landscape = width > height
        orientation = if (landscape) HORIZONTAL else VERTICAL
        val art = artwork.layoutParams as LayoutParams
        val size = if (landscape) minOf(width * 36 / 100,height * 80 / 100,dp(220))
                   else minOf(width * 62 / 100,height * 33 / 100,dp(220))
        art.width = size; art.height = size
        art.rightMargin = if (landscape) dp(16) else 0
        art.bottomMargin = if (landscape) 0 else dp(12)
        val body = controls.layoutParams as LayoutParams
        body.width = if (landscape) 0 else LayoutParams.MATCH_PARENT
        body.weight = if (landscape) 1f else 0f
        val available = if (landscape) width - art.width - art.rightMargin else width
        // Use the natural width of the translated labels at the user's type size.
        // A narrow landscape column can need stacked actions even on a wide screen.
        var rowWidth = dp(6)
        for (i in 0 until actions.childCount) {
            val button = actions.getChildAt(i) as Button
            val textWidth = ceil(Layout.getDesiredWidth(button.text,button.paint)).toInt()
            rowWidth += maxOf(button.minimumWidth,textWidth + button.compoundPaddingLeft + button.compoundPaddingRight)
        }
        val inline = rowWidth <= available
        actions.orientation = if (inline) HORIZONTAL else VERTICAL
        (actions.getChildAt(1).layoutParams as LayoutParams).marginStart = if (inline) dp(6) else 0
        super.onMeasure(widthSpec,heightSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val generation = ++loadId
        val assets = context.applicationContext.assets
        val target = dp(220).coerceIn(1,768)
        worker.execute {
            val drawable = try {
                val source = ImageDecoder.createSource(assets,"sugar-splash/startup-failure.webp")
                ImageDecoder.decodeDrawable(source) { decoder,_,_ -> decoder.setTargetSize(target,target) } as? AnimatedImageDrawable
            } catch (_: java.io.IOException) { null }
            main.post {
                if (loadId == generation && isAttachedToWindow && drawable != null) {
                    animation = drawable
                    artwork.setImageDrawable(drawable)
                    animateIfVisible()
                }
            }
        }
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        showing = isVisible
        animateIfVisible()
    }
    private fun animateIfVisible() {
        animation?.let { if (showing && foreground) it.start() else it.stop() }
    }
    override fun onDetachedFromWindow() {
        ++loadId
        animation?.stop()
        artwork.setImageDrawable(null)
        animation = null
        super.onDetachedFromWindow()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { Thread(it,"SugarFailureArt").apply { isDaemon = true } }
    }
}

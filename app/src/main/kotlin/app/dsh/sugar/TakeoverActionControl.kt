package app.dsh.sugar

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** A 32dp visible button inside a 48dp touch target, with one centered icon-label group. */
internal class TakeoverActionControl(context: Context, glyph: MobileIcon, private val emphasized: Boolean) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private data class Palette(val ink: Int, val tone: Int, val tint: Int, val dark: Boolean)
    private data class Appearance(val enabled: Boolean, val current: Boolean, val palette: Palette)
    private val initiallyDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private var palette = Palette(if (initiallyDark) Color.WHITE else 0xff23212b.toInt(),
        if (initiallyDark) 0xffc4bcff.toInt() else 0xff6860ad.toInt(),
        if (initiallyDark) 0xff273044.toInt() else 0xfff7f6fc.toInt(), initiallyDark)
    private var current = false
    private var appearance: Appearance? = null
    private val icon = MobileControlIcon(glyph, palette.ink)
    private val symbol = ImageView(context).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val label = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        minimumHeight = dp(48)
        setPadding(dp(9), dp(8), dp(9), dp(8))
        isFocusable = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            addView(symbol, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) })
            addView(label, LinearLayout.LayoutParams(-2, -2))
        }
        addView(group, LayoutParams(-2, -2, Gravity.CENTER))
        render()
    }

    fun setPalette(ink: Int, tone: Int, material: SugarGlassMaterial) {
        val next = Palette(ink, tone, material.tint, material.dark)
        if (palette == next) return
        palette = next
        render()
    }

    fun bind(title: String, enabled: Boolean, current: Boolean = false) {
        if (label.text.toString() != title) label.text = title
        contentDescription = title
        isEnabled = enabled
        isClickable = enabled
        isFocusable = enabled
        this.current = current
        render()
    }

    private fun readableInk(fill: Int, preferred: Int): Int {
        fun contrast(a: Int, b: Int): Double {
            val light = maxOf(Color.luminance(a), Color.luminance(b))
            val dark = minOf(Color.luminance(a), Color.luminance(b))
            return (light + .05) / (dark + .05)
        }
        if (contrast(fill, preferred) >= 4.5) return preferred
        return if (contrast(fill, Color.WHITE) > contrast(fill, Color.BLACK)) Color.WHITE else Color.BLACK
    }

    private fun render() {
        val next = Appearance(isEnabled, current, palette)
        if (appearance == next) return
        appearance = next
        val p = palette
        val amount = when {
            emphasized && isEnabled -> if (p.dark) .36f else .32f
            current -> .14f
            isEnabled -> .10f
            else -> .065f
        }
        val mixed = SugarGlassMaterial.blend(p.tint, p.tone, amount)
        val opacity = when {
            emphasized && isEnabled -> .96
            current -> .68
            isEnabled -> .58
            else -> .36
        }
        val visibleFill = SugarGlassMaterial.blend(p.tint, mixed, opacity.toFloat())
        val preferred = if (isEnabled || current) p.ink else SugarGlassMaterial.blend(p.ink, p.tint, .35f)
        val foreground = readableInk(visibleFill, preferred)
        label.setTextColor(foreground)
        icon.setInk(foreground)
        val fill = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(SugarGlassMaterial.withAlpha(mixed, opacity))
            if (emphasized || this@TakeoverActionControl.current) setStroke(dp(1).coerceAtLeast(1),
                SugarGlassMaterial.withAlpha(p.tone, if (isEnabled) .24 else .12))
        }
        val mask = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) }
        val ripple = RippleDrawable(ColorStateList.valueOf(SugarGlassMaterial.withAlpha(p.tone, .18)), fill, mask)
        background = InsetDrawable(ripple, 0, dp(8), 0, dp(8))
        // A new InsetDrawable can replace the view's padding with its own inset padding.
        setPadding(dp(9), dp(8), dp(9), dp(8))
    }

    override fun performClick(): Boolean = if (isEnabled) super.performClick() else false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
        info.text = label.text
        info.isEnabled = isEnabled
        info.isClickable = isEnabled && isClickable
    }
}

package app.dsh.sugar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Native rows remain usable when DSH and its WebView are unavailable. */
internal object RecoveryActionGroup {
    fun addTo(
        parent: LinearLayout,
        onCurrent: () -> Unit,
        onBuiltin: () -> Unit,
        onRestore: () -> Unit,
        onReset: () -> Unit,
    ): List<View> {
        val context = parent.context
        val paper = context.getColor(R.color.sugar_paper)
        val ink = context.getColor(R.color.sugar_ink)
        val muted = context.getColor(R.color.sugar_secondary)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        fun group(title: Int): LinearLayout {
            parent.addView(TextView(context).apply {
                text = context.sugarString(title)
                textSize = 13f
                setTextColor(muted)
                setTypeface(typeface, Typeface.BOLD)
                isAccessibilityHeading = true
                setPaddingRelative(dp(2), dp(16), dp(2), dp(7))
            })
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tint(paper, ink, .032f), 16).apply {
                    setStroke(dp(1), tint(paper, ink, .11f))
                }
                clipToOutline = true
                parent.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        fun row(group: LinearLayout, title: Int, note: Int, icon: Int, tone: Int, action: () -> Unit): View {
            if (group.childCount > 0) group.addView(View(context).apply {
                setBackgroundColor(tint(paper, ink, .1f))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(62); marginEnd = dp(14) })
            val row = Action(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(72)
                val selectable = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                if (selectable.resourceId != 0) setBackgroundResource(selectable.resourceId)
                else background = RippleDrawable(ColorStateList.valueOf(tint(paper, tone, .15f)), null, null)
                contentDescription = listOf(context.sugarString(title), context.sugarString(note)).joinToString("\n")
                isFocusable = true
                setOnClickListener { action() }
            }
            val badge = ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tone)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = rounded(tint(paper, tone, .105f), 12)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)))
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            labels.addView(TextView(context).apply {
                text = context.sugarString(title)
                textSize = 16f
                setTextColor(ink)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            labels.addView(TextView(context).apply {
                text = context.sugarString(note)
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(6) })
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_recovery_next)
                imageTintList = ColorStateList.valueOf(muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(14), dp(14)))
            group.addView(row, LinearLayout.LayoutParams(-1, -2))
            return row
        }
        val diagnose = group(R.string.diag_group_diagnose)
        val cool = context.getColor(R.color.sugar_diag_ink)
        val current = row(diagnose, R.string.diag_current, R.string.diag_current_note, R.drawable.ic_recovery_flask, cool, onCurrent)
        val builtin = row(diagnose, R.string.diag_builtin, R.string.diag_builtin_note, R.drawable.ic_recovery_package, cool, onBuiltin)
        val restore = group(R.string.diag_group_restore)
        val warm = context.getColor(R.color.sugar_diag_restore_ink)
        val program = row(restore, R.string.diag_restore, R.string.diag_restore_note, R.drawable.ic_recovery_restore, warm, onRestore)
        val reset = row(restore, R.string.diag_reset, R.string.diag_reset_note, R.drawable.ic_recovery_sliders, warm, onReset)
        return listOf(current, builtin, program, reset)
    }

    private class Action(context: Context) : LinearLayout(context) {
        override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else .55f
        }
    }

    private fun tint(base: Int, tone: Int, fraction: Float): Int {
        fun channel(a: Int, b: Int) = (a + (b - a) * fraction).roundToInt()
        return Color.rgb(channel(Color.red(base), Color.red(tone)), channel(Color.green(base), Color.green(tone)),
            channel(Color.blue(base), Color.blue(tone)))
    }
}

package app.dsh.sugar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** Listening and recognition occupy the space of the choices, keeping the paper actions reachable. */
internal class PetRecordingStatus(context: Context, theme: PetNoteTheme) : LinearLayout(context) {
    private val meter = InkWave(context, theme)
    private val caption = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(theme.ink)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var phase: PetVoicePhase? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(meter, LayoutParams(LayoutParams.MATCH_PARENT, (15 * resources.displayMetrics.density).roundToInt()))
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setPhase(value: PetVoicePhase?) {
        if (phase == value) return
        phase = value
        meter.clear()
        visibility = if (value == null) GONE else VISIBLE
        caption.text = when (value) {
            PetVoicePhase.PREPARING -> context.sugarString(R.string.pet_voice_preparing_note)
            PetVoicePhase.RECORDING -> context.sugarString(R.string.pet_voice_recording)
            PetVoicePhase.TRANSCRIBING -> context.sugarString(R.string.pet_voice_transcribing)
            null -> ""
        }
        requestLayout()
    }

    fun setLevel(level: Float) {
        if (phase == PetVoicePhase.RECORDING) meter.append(level)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val room = MeasureSpec.getSize(heightMeasureSpec)
        val showMeter = phase == PetVoicePhase.RECORDING &&
            room >= (caption.paint.fontMetricsInt.let { it.descent - it.ascent } + meter.layoutParams.height)
        meter.visibility = if (showMeter) VISIBLE else GONE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Only real audio updates move the ink. Silence leaves a fine dotted line. */
    private class InkWave(context: Context, private val theme: PetNoteTheme) : View(context) {
        private val levels = FloatArray(15)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val density = resources.displayMetrics.density

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun append(level: Float) {
            levels.copyInto(levels, endIndex = levels.size, startIndex = 1)
            levels[levels.lastIndex] = level.coerceIn(0f, 1f)
            invalidate()
        }

        fun clear() {
            levels.fill(0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val step = min(3.1f * density, width / (levels.size + 1f))
            val start = (width - step * (levels.size - 1)) / 2f
            val middle = height * .46f
            val range = min(5.2f * density, middle * .8f)
            ink.strokeWidth = min(1.55f * density, step * .65f)
            for (i in levels.indices) {
                ink.color = if (i == levels.lastIndex) theme.accent else theme.wash(0xc0)
                val x = start + i * step
                val h = .2f * density + range * levels[i]
                canvas.drawLine(x, middle - h, x, middle + h, ink)
            }
        }
    }
}

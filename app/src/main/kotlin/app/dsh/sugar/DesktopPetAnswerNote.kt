package app.dsh.sugar

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A pet-sized answer sheet. Its host owns its position and the results of each action. */
internal class DesktopPetAnswerNote(
    context: Context,
    packId: String,
    private val onFold: () -> Unit,
    private val onVoice: () -> Unit,
    private val onConfirm: (List<Int>) -> Unit,
) : ViewGroup(context) {
    private val theme = PetNoteTheme.forPack(packId)

    private val page = View(context).apply {
        background = theme.paper(context)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true
        scrollBarDefaultDelayBeforeFade = 160
        scrollBarFadeDuration = 220
        scrollBarSize = maxOf(1, dp(1.5f))
        verticalScrollbarThumbDrawable = GradientDrawable().apply {
            setColor(theme.wash(0x35))
            cornerRadius = dp(2f).toFloat()
        }
        verticalScrollbarTrackDrawable = ColorDrawable(Color.TRANSPARENT)
        overScrollMode = OVER_SCROLL_NEVER
        addView(rows)
    }
    private val selected = mutableSetOf<Int>()
    var voiceText: String? = null
        private set
    private var voiceIndex = -1
    private var voicePhase: PetVoicePhase? = null
    private var cancelCapture: () -> Unit = {}
    private var endCapture: () -> Unit = {}
    private val recordingStatus = PetRecordingStatus(context, theme).apply { visibility = GONE }
    private val mic = action(theme.mic, R.string.pet_answer_voice) {
        if (voicePhase == null) onVoice() else cancelCapture()
    }
    private val confirm = action(theme.confirm, R.string.pet_answer_confirm) {
        if (voicePhase == PetVoicePhase.RECORDING) endCapture()
        else if (voicePhase == null && selected.isNotEmpty()) onConfirm(selected.sorted())
    }
    private var multiple = false
    private var voiceAvailable = true
    private var interactive = true

    private var noteOnLeft = true
    private val clipRect = Rect()
    private val clip: View = (theme.clasp?.let { icon ->
        ImageButton(context).apply {
            setPadding(0, 0, 0, 0)
            background = paperRipple(theme)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(icon)
        }
    } ?: PetPaperClip(context, theme)).apply {
        contentDescription = context.sugarString(R.string.pet_answer_fold)
        setOnClickListener { onFold() }
    }

    fun setPlacement(onLeft: Boolean) {
        if (noteOnLeft == onLeft) return
        noteOnLeft = onLeft
        clip.scaleX = if (onLeft) 1f else -1f
        requestLayout()
    }

    fun setVoiceAvailable(available: Boolean) {
        if (voiceAvailable == available) return
        voiceAvailable = available
        updateActions()
        requestLayout()
    }

    fun setVoicePhase(phase: PetVoicePhase?, onCancel: () -> Unit = {}, onDone: () -> Unit = {}) {
        cancelCapture = onCancel
        endCapture = onDone
        if (voicePhase == phase) return
        voicePhase = phase
        recordingStatus.setPhase(phase)
        scroll.visibility = if (phase == null) VISIBLE else GONE
        mic.setImageResource(if (phase == null) theme.mic else R.drawable.pet_answer_voice_cancel)
        mic.imageTintList = if (phase == null) null else ColorStateList.valueOf(theme.ink)
        mic.contentDescription = context.sugarString(if (phase == null) R.string.pet_answer_voice else R.string.pet_voice_discard_note)
        confirm.contentDescription = context.sugarString(if (phase == null) R.string.pet_answer_confirm else R.string.pet_voice_done)
        updateActions()
        requestLayout()
    }

    fun setVoiceLevel(level: Float) = recordingStatus.setLevel(level)

    private val pageRect = Rect()
    private val listRect = Rect()
    private val micRect = Rect()
    private val confirmRect = Rect()

    init {
        addView(page)
        addView(scroll)
        addView(recordingStatus)
        addView(mic)
        addView(confirm)
        addView(clip)
        updateActions()
    }

    fun bind(options: List<String>, multiple: Boolean = false, voiceAvailable: Boolean = true) {
        setVoicePhase(null)
        this.multiple = multiple
        this.voiceAvailable = voiceAvailable
        selected.clear()
        voiceText = null
        voiceIndex = -1
        rows.removeAllViews()
        options.forEachIndexed { index, option ->
            val row = makeRow(index, option)
            rows.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36f)))
        }
        scroll.scrollTo(0, 0)
        updateActions()
        requestLayout()
    }

    /** The optional voice answer has a stable index just past the last supplied option. */
    fun setVoiceText(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty() || !voiceAvailable) return
        if (voiceIndex < 0) voiceIndex = rows.childCount else rows.removeViewAt(voiceIndex)
        voiceText = spoken
        val row = makeRow(voiceIndex, spoken)
        rows.addView(row, voiceIndex, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36f)))
        selected.clear()
        selected.add(voiceIndex)
        updateSelection()
        requestLayout()
        row.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                        oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                row.removeOnLayoutChangeListener(this)
                if (row.parent === rows) scroll.scrollTo(0, row.bottom - scroll.height)
            }
        })
    }

    private fun makeRow(index: Int, text: String) = PetAnswerRow(context, text, multiple, theme).apply {
        setOnClickListener { toggle(index) }
    }

    fun setInteractive(enabled: Boolean) {
        interactive = enabled
        for (i in 0 until rows.childCount) rows.getChildAt(i).isEnabled = enabled
        updateActions()
    }

    fun preferredWidth(petPx: Int): Int = minOf(dp(218f), (petPx * 1.46f).roundToInt())

    // Scale to the visible character, including its artwork's transparent top and bottom margin.
    fun preferredHeight(petPx: Int): Int {
        val cap = (petPx * .895f).roundToInt().coerceAtLeast(1)
        val fixed = dp(36f)
        val minRow = rowMinimum()
        val typical = ((cap - fixed) / 3).coerceIn(minRow, maxOf(minRow, dp(36f)))
        val choices = fixed + typical * rows.childCount
        return minOf(cap, if (voicePhase == null) choices else maxOf(choices, dp(72f)))
    }

    private fun rowMinimum() = maxOf(dp(20f), ceil(TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics
    ).toDouble()).toInt())

    private fun toggle(index: Int) {
        if (multiple && index != voiceIndex) {
            selected.remove(voiceIndex)
            if (!selected.add(index)) selected.remove(index)
        } else {
            selected.clear()
            selected.add(index)
        }
        updateSelection()
    }

    private fun updateSelection() {
        for (i in 0 until rows.childCount) (rows.getChildAt(i) as PetAnswerRow).setMarked(i in selected)
        updateActions()
    }

    private fun updateActions() {
        mic.visibility = if (voiceAvailable || voicePhase != null) VISIBLE else GONE
        mic.isEnabled = interactive
        confirm.isEnabled = interactive && when (voicePhase) {
            null -> selected.isNotEmpty()
            PetVoicePhase.RECORDING -> true
            else -> false
        }
        confirm.alpha = if (confirm.isEnabled) 1f else .38f
    }

    private fun action(icon: Int, label: Int, callback: () -> Unit) = ImageButton(context).apply {
        setPadding(0, 0, 0, 0)
        background = paperRipple(theme)
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.sugarString(label)
        setOnClickListener { callback() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(preferredWidth(dp(112f)), widthMeasureSpec)
        val h = resolveSize(preferredHeight(dp(112f)), heightMeasureSpec)
        setMeasuredDimension(w, h)
        val compact = w < dp(96f)
        val clipMargin = minOf(dp(18f), w / 5)
        val outer = minOf(dp(4f), clipMargin / 3)
        pageRect.set(if (noteOnLeft) 0 else outer, 0, if (noteOnLeft) w - outer else w, h)
        val clipHeight = minOf(dp(44f), h)
        clipRect.set(if (noteOnLeft) w - clipMargin else 0, (h - clipHeight) / 2,
            if (noteOnLeft) w else clipMargin, (h + clipHeight) / 2)
        val left = if (noteOnLeft) minOf(if (compact) dp(8f) else dp(13f), w / 4) else clipMargin
        val right = (w - if (noteOnLeft) clipMargin else minOf(dp(5f), w / 5)).coerceAtLeast(left)
        val top = minOf(dp(5f), h / 8)
        val bottom = (h - minOf(dp(4f), h / 8)).coerceAtLeast(top)
        val hasBody = rows.childCount > 0 || voicePhase != null
        val footer = minOf(dp(if (voicePhase == null) 27f else 30f), if (hasBody) (bottom - top) / 2 else bottom - top)
        val footerTop = bottom - footer
        listRect.set(left, top, right, footerTop)
        val actionWidth = minOf(dp(43f), (right - left) / (if (mic.visibility == VISIBLE) 2 else 1))
        confirmRect.set(right - actionWidth, footerTop, right, bottom)
        micRect.set(right - actionWidth * 2, footerTop, right - actionWidth, bottom)

        val rowCount = rows.childCount.coerceIn(1, 3)
        val rowHeight = maxOf(rowMinimum(), listRect.height() / rowCount)
            .coerceAtMost(maxOf(rowMinimum(), dp(36f)))
        for (i in 0 until rows.childCount) {
            val row = rows.getChildAt(i) as PetAnswerRow
            if (row.layoutParams.height != rowHeight) {
                row.layoutParams.height = rowHeight
                row.forceLayout()
                rows.forceLayout()
            }
            row.setCompact(compact)
        }
        measureIn(clip, clipRect)
        measureIn(page, pageRect)
        if (voicePhase == null) measureIn(scroll, listRect) else measureIn(recordingStatus, listRect)
        if (mic.visibility == VISIBLE) measureAction(mic, micRect, compact)
        measureAction(confirm, confirmRect, compact)
    }

    private fun measureAction(button: ImageButton, rect: Rect, compact: Boolean) {
        val iconSize = minOf(if (compact) dp(17f) else dp(23f), rect.width(), rect.height())
        val px = (rect.width() - iconSize) / 2
        val py = (rect.height() - iconSize) / 2
        button.setPadding(px, py, px, py)
        measureIn(button, rect)
    }

    private fun measureIn(view: View, rect: Rect) {
        view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(clip, clipRect)
        place(page, pageRect)
        if (voicePhase == null) place(scroll, listRect) else place(recordingStatus, listRect)
        if (mic.visibility == VISIBLE) place(mic, micRect)
        place(confirm, confirmRect)
    }

    private fun place(view: View, rect: Rect) = view.layout(rect.left, rect.top, rect.right, rect.bottom)
}

private fun View.dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

private fun paperRipple(theme: PetNoteTheme) =
    RippleDrawable(ColorStateList.valueOf(theme.wash(0x26)), null, ColorDrawable(Color.WHITE))

private class PetAnswerRow(context: Context, title: String, private val multiple: Boolean, private val theme: PetNoteTheme) : FrameLayout(context) {
    private val line = PetAnswerLine(context, title, theme)
    private val mark = ImageView(context).apply {
        setImageResource(theme.mark)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var compact = false
    private var marked = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        contentDescription = title
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        foreground = paperRipple(theme)
        setWillNotDraw(false)
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(mark, LayoutParams(dp(13f), dp(13f), Gravity.LEFT or Gravity.CENTER_VERTICAL))
        mark.visibility = INVISIBLE
        updateInsets()
    }

    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        updateInsets()
    }

    fun setMarked(value: Boolean) {
        if (marked == value) return
        marked = value
        mark.visibility = if (value) VISIBLE else INVISIBLE
        isSelected = value
        updateInsets()
        invalidate()
    }

    private fun updateInsets() {
        val markSize = dp(if (compact) 10f else 13f)
        val left = if (!compact || marked) markSize + dp(2f) else dp(2f)
        line.setPadding(left, 0, dp(3f), 0)
        line.configure(marked, compact)
        val markParams = mark.layoutParams as LayoutParams
        markParams.width = markSize
        markParams.height = markSize
        mark.layoutParams = markParams
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (marked) {
            paint.color = theme.wash(0x20)
            val r = dp(3f).toFloat()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        }
        paint.color = theme.wash(0x32)
        val y = height - .5f * resources.displayMetrics.density
        paint.strokeWidth = maxOf(1f, .6f * resources.displayMetrics.density)
        canvas.drawLine(0f, y, width.toFloat(), y, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = if (multiple) "android.widget.CheckBox" else "android.widget.RadioButton"
        info.isCheckable = true
        info.isChecked = marked
    }
}

/** The selected line pans independently of focus, including in a non-focusable overlay window. */
private class PetAnswerLine(context: Context, title: String, private val theme: PetNoteTheme) : View(context) {
    private val text = title.lineSequence().joinToString(" ")
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var layout: StaticLayout? = null
    private var active = false
    private var startTime = 0L
    private var overflow = 0
    private var naturalWidth = 0

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(marked: Boolean, compact: Boolean) {
        active = marked
        ink.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            if (compact) 11.5f else 13f, resources.displayMetrics)
        ink.color = if (marked) theme.ink else theme.text
        ink.typeface = if (marked) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        naturalWidth = ceil(Layout.getDesiredWidth(text, ink).toDouble()).toInt()
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val animated = active && ValueAnimator.areAnimatorsEnabled()
        val shown = if (animated) text else TextUtils.ellipsize(text, ink, available.toFloat(), TextUtils.TruncateAt.END)
        val lineWidth = if (animated) maxOf(available, naturalWidth) else available
        overflow = lineWidth - available
        layout = StaticLayout.Builder.obtain(shown, 0, shown.length, ink, lineWidth)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setMaxLines(1)
            .build()
        startTime = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            startTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val body = layout ?: return
        val save = canvas.save()
        canvas.clipRect(paddingLeft, 0, width - paddingRight, height)
        var offset = 0f
        if (active && overflow > 0 && ValueAnimator.areAnimatorsEnabled()) {
            val hold = 950L
            val duration = maxOf(1L, (overflow * 1000f / dp(28f)).toLong())
            val phase = (SystemClock.uptimeMillis() - startTime) % (2 * (duration + hold))
            offset = when {
                phase < hold -> 0f
                phase < hold + duration -> overflow * (phase - hold) / duration.toFloat()
                phase < 2 * hold + duration -> overflow.toFloat()
                else -> overflow * (2 * (duration + hold) - phase) / duration.toFloat()
            }
            when {
                phase < hold -> postInvalidateDelayed(hold - phase)
                phase in (hold + duration) until (2 * hold + duration) ->
                    postInvalidateDelayed(2 * hold + duration - phase)
                else -> postInvalidateOnAnimation()
            }
        }
        if (body.getParagraphDirection(0) < 0) offset = overflow - offset
        canvas.translate(paddingLeft - offset, (height - body.height) / 2f)
        body.draw(canvas)
        canvas.restoreToCount(save)
    }
}

/** The sleep journal has a bent wire hook with a tiny brass crescent. */
private class PetPaperClip(context: Context, theme: PetNoteTheme) : View(context) {
    private val wire = Path().apply {
        moveTo(5.7f, 8.8f)
        lineTo(5.7f, 20.7f)
        cubicTo(5.7f, 27.4f, 14.1f, 27.4f, 14.1f, 20.7f)
        lineTo(14.1f, 6.7f)
        cubicTo(14.1f, 1.4f, 7.8f, 1.4f, 7.8f, 6.7f)
        lineTo(7.8f, 19.7f)
        cubicTo(7.8f, 22.9f, 11.9f, 22.9f, 11.9f, 19.7f)
        lineTo(11.9f, 9.8f)
    }
    private val crescent = Path().apply {
        moveTo(6.7f, 4.7f)
        cubicTo(4.2f, 4.5f, 2.8f, 6.2f, 3.1f, 8.0f)
        cubicTo(3.5f, 10.4f, 6.0f, 11.1f, 7.8f, 9.8f)
        cubicTo(5.0f, 9.3f, 4.4f, 6.3f, 6.7f, 4.7f)
        close()
    }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    init { isFocusable = true; foreground = paperRipple(theme) }
    override fun getAccessibilityClassName() = "android.widget.Button"
    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width / 17f, height / 30f, resources.displayMetrics.density)
        val save = canvas.save()
        canvas.translate((width - 17 * scale) / 2, (height - 30 * scale) / 2)
        canvas.scale(scale, scale)
        canvas.rotate(8f, 9.5f, 15f)
        pen.style = Paint.Style.STROKE
        pen.strokeWidth = 1.65f
        pen.color = 0xff95869f.toInt()
        canvas.drawPath(wire, pen)
        canvas.translate(-.19f, -.19f)
        pen.strokeWidth = .58f
        pen.color = 0xffe5ddec.toInt()
        canvas.drawPath(wire, pen)
        pen.style = Paint.Style.FILL
        pen.color = 0xffcdae78.toInt()
        canvas.drawPath(crescent, pen)
        canvas.restoreToCount(save)
    }
}

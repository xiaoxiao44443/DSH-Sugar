package app.dsh.sugar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.icu.text.BreakIterator
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextDirectionHeuristics
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

internal object DesktopPetSpeech {
    private const val QUICK_REPLIES = "quick-replies"
    private val libraries = mapOf(
        "idle" to R.array.pet_speech_idle,
        "poke" to R.array.pet_speech_poke,
        "phone" to R.array.pet_speech_phone,
        "coding" to R.array.pet_speech_coding,
        "thinking" to R.array.pet_speech_thinking,
        "asking" to R.array.pet_speech_asking,
        "working" to R.array.pet_speech_working,
        QUICK_REPLIES to R.array.pet_quick_replies,
    )
    val categories: Set<String> get() = libraries.keys

    fun quickReplies(context: Context): List<String> = lines(context, QUICK_REPLIES)

    fun defaults(context: Context, state: String): List<String> =
        libraries[state]?.let { SugarLanguage.resources(context).getStringArray(it).toList() }.orEmpty()

    fun lines(context: Context, state: String): List<String> {
        val saved = DesktopPets.prefs(context).getString("speech_$state", null)
            ?: return defaults(context, state)
        return try {
            val array = JSONArray(saved)
            (0 until array.length()).mapNotNull { array.opt(it) as? String }
        } catch (_: JSONException) {
            defaults(context, state)
        }
    }

    fun save(context: Context, state: String, lines: List<String>) {
        if (state !in libraries) return
        val phrases = lines.flatMap { it.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() }
        DesktopPets.prefs(context).edit().putString("speech_$state", JSONArray(phrases).toString()).apply()
    }

    fun reset(context: Context, state: String) {
        DesktopPets.prefs(context).edit().remove("speech_$state").apply()
    }

    fun choose(context: Context, state: String): String? = lines(context, state).randomOrNull()
}

/** Each checkpoint reveals one whole character; the viewport glides between checkpoints. */
internal class PetBubbleFirstPass(carets: FloatArray, pauses: LongArray, viewport: Int, lineWidth: Int, textSize: Float) {
    val offsets = FloatArray(carets.size)
    val reveals = LongArray(carets.size)
    val moves = LongArray(carets.size)
    val totalMs get() = reveals.last()

    init {
        val overflow = (lineWidth - viewport).coerceAtLeast(0).toFloat()
        offsets[0] = carets[0].coerceIn(0f, overflow)
        var hasPanned = false
        for (i in 1 until carets.size) {
            val before = offsets[i - 1]
            offsets[i] = before.coerceIn(carets[i] - viewport, carets[i]).coerceIn(0f, overflow)
            val distance = abs(offsets[i] - before)
            val firstPan = !hasPanned && distance > 0f
            moves[i] = reveals[i - 1] + when {
                firstPan -> 1_000L
                hasPanned -> pauses[i - 1]
                else -> 0L
            }
            if (distance > 0f) hasPanned = true
            // Five ems per second at the current font scale. Keep drag-like motion smooth between letters.
            val travelMs = ceil(1000.0 * distance / (5f * textSize)).toLong()
            reveals[i] = moves[i] + when {
                i == 1 && !hasPanned -> 0L
                hasPanned -> travelMs.coerceAtLeast(48L)
                else -> 48L
            }
        }
    }

    fun shownAt(elapsed: Long): Int {
        var low = 0
        var high = reveals.size
        while (low + 1 < high) {
            val mid = (low + high) ushr 1
            if (reveals[mid] <= elapsed) low = mid else high = mid
        }
        return low
    }

    fun offsetAt(shown: Int, elapsed: Long): Float {
        if (shown == offsets.lastIndex || offsets[shown] == offsets[shown + 1]) return offsets[shown]
        val i = shown + 1
        val fraction = ((elapsed - moves[i]).toFloat() / (reveals[i] - moves[i])).coerceIn(0f, 1f)
        return offsets[shown] + fraction * (offsets[i] - offsets[shown])
    }
}

internal class DesktopPetBubble(context: Context) : FrameLayout(context) {
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(53, 52, 77)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val detailInk = Paint(ink).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        alpha = 180
        textAlign = Paint.Align.CENTER
    }
    private var detail: String? = null
    private val detailHeight get() = if (detail == null) 0 else detailInk.fontMetricsInt.run { bottom - top } + dp(1)
    private val revealClip = Path()
    private var line: StaticLayout? = null
    private var message = ""
    private var boundaries = intArrayOf()
    private var firstPass: PetBubbleFirstPass? = null
    private var firstPassWidth = 0
    private var firstPassStartedAt = 0L
    private var shown = 0
    private var offset = 0f
    private var towardRight = false
    private var lastStep = 0L
    private var running = false
    private val maxWidthPx = dp(200)
    private val speed = dp(35).toFloat()
    private val available get() = (width - paddingLeft - paddingRight).coerceAtLeast(1)
    private val overflow get() = ((line?.width ?: 0) - available).coerceAtLeast(0).toFloat()

    init {
        setBackgroundResource(R.drawable.sugar_pet_bubble)
        setWillNotDraw(false)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = View.GONE
    }

    fun showText(text: String?) {
        val next = text.orEmpty().lineSequence().joinToString(" ").trim()
        if (next == message) return
        stopPlayback()
        message = next
        updateDescription()
        shown = 0
        offset = 0f
        line = null
        firstPass = null
        if (next.isEmpty()) {
            boundaries = intArrayOf()
            visibility = View.GONE
            return
        }
        val iterator = BreakIterator.getCharacterInstance().apply { setText(next) }
        val ends = mutableListOf<Int>()
        iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            ends.add(end)
            end = iterator.next()
        }
        boundaries = ends.toIntArray()
        val naturalWidth = ceil(Layout.getDesiredWidth(next, ink).toDouble()).toInt().coerceAtLeast(1)
        line = StaticLayout.Builder.obtain(next, 0, next.length, ink, naturalWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .build()
        visibility = View.VISIBLE
        requestLayout()
        invalidate()
        startWhenVisible()
    }

    // The small live label has its own paint; updates don't restart typing or scrolling.
    fun showDetail(value: String?) {
        if (detail == value) return
        detail = value
        updateDescription()
        requestLayout()
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = if (message.isEmpty()) null else detail?.let { "$message\n$it" } ?: message
    }

    /** Time for one full read, including one return across any overflowing text. */
    fun readingDurationMs(): Long {
        val body = line ?: return 0L
        val viewport = ((measuredWidth.takeIf { it > 0 } ?: maxWidthPx) - paddingLeft - paddingRight).coerceAtLeast(1)
        val extra = (body.width - viewport).coerceAtLeast(0)
        val travel = if (extra > 0) (2000.0 * extra / speed).toLong() + 2 * edgeHold else 0L
        return firstPassDurationMs() + travel
    }

    private fun firstPassFor(viewport: Int): PetBubbleFirstPass? {
        val body = line ?: return null
        if (firstPassWidth != viewport || firstPass == null) {
            val carets = FloatArray(boundaries.size + 1) { i -> body.getPrimaryHorizontal(if (i == 0) 0 else boundaries[i - 1]) }
            val pauses = LongArray(carets.size) { i ->
                when (if (i == 0) ' ' else message[boundaries[i - 1] - 1]) {
                    '。', '！', '？', '…', '.', '!', '?' -> 180L
                    '，', '、', '；', '：', ',', ';', ':' -> 90L
                    else -> 0L
                }
            }
            firstPass = PetBubbleFirstPass(carets, pauses, viewport, body.width, ink.textSize)
            firstPassWidth = viewport
        }
        return firstPass
    }

    fun firstPassDurationMs(): Long {
        val viewport = ((measuredWidth.takeIf { it > 0 } ?: maxWidthPx) - paddingLeft - paddingRight).coerceAtLeast(1)
        return appearTime + (firstPassFor(viewport)?.totalMs ?: 0L) + 1800L
    }

    fun heightWithFrameInsets(verticalInsets: Int) = (line?.height ?: 0) + detailHeight + verticalInsets

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val body = line
        val detailWidth = detail?.let { ceil(detailInk.measureText(it)).toInt() } ?: 0
        val desired = (maxOf(body?.width ?: 0, detailWidth) + paddingLeft + paddingRight).coerceIn(dp(52), maxWidthPx)
        val height = heightWithFrameInsets(paddingTop + paddingBottom)
        setMeasuredDimension(min(maxWidthPx, resolveSize(desired, widthMeasureSpec)), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val body = line ?: return
        if (shown == 0 || available <= 0) return
        val save = canvas.save()
        canvas.clipRect(paddingLeft.toFloat(), 0f, (width - paddingRight).toFloat(), height.toFloat())
        val center = ((available - body.width).coerceAtLeast(0)) * .5f
        canvas.translate(paddingLeft + center - offset, paddingTop + (height - paddingTop - paddingBottom - body.height - detailHeight) * .5f)
        if (shown < boundaries.size) {
            revealClip.reset()
            body.getSelectionPath(0, boundaries[shown - 1], revealClip)
            canvas.clipPath(revealClip)
        }
        body.draw(canvas)
        canvas.restoreToCount(save)
        detail?.let {
            val saved = canvas.save()
            canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
            canvas.drawText(it, paddingLeft + available / 2f,
                (height - paddingBottom - detailInk.fontMetricsInt.bottom).toFloat(), detailInk)
            canvas.restoreToCount(saved)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) startWhenVisible() else stopPlayback()
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        offset = offset.coerceIn(0f, overflow)
        if (running && shown == boundaries.size) schedulePan()
    }

    private fun startWhenVisible() {
        if (running || line == null || !isAttachedToWindow || !isShown || windowVisibility != View.VISIBLE) return
        running = true
        shown = 0
        offset = 0f
        alpha = 0f
        scaleX = .95f
        scaleY = .95f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(appearTime).start()
        firstPassStartedAt = SystemClock.uptimeMillis() + appearTime
        postDelayed(typeNext, appearTime)
        invalidate()
    }

    private fun stopPlayback() {
        running = false
        removeCallbacks(typeNext)
        removeCallbacks(scrollStep)
        animate().cancel()
    }

    private val typeNext = object : Runnable {
        override fun run() {
            if (!running || shown >= boundaries.size) return
            val pass = firstPassFor(available) ?: return
            val elapsed = (SystemClock.uptimeMillis() - firstPassStartedAt).coerceAtLeast(0L)
            shown = pass.shownAt(elapsed)
            offset = pass.offsetAt(shown, elapsed)
            invalidate()
            if (shown == boundaries.size) {
                schedulePan()
                return
            }
            val next = shown + 1
            val nextChange = if (pass.offsets[shown] == pass.offsets[next]) pass.reveals[next] else pass.moves[next]
            if (nextChange > elapsed) postDelayed(this, nextChange - elapsed) else postOnAnimation(this)
        }
    }

    private fun schedulePan() {
        removeCallbacks(scrollStep)
        if (overflow <= 0f) return
        towardRight = offset < overflow / 2
        lastStep = 0
        postDelayed(scrollStep, edgeHold)
    }

    private val scrollStep = object : Runnable {
        override fun run() {
            if (!running || overflow == 0f) return
            val now = SystemClock.uptimeMillis()
            val step = if (lastStep == 0L) 0f else speed * (now - lastStep) / 1000f
            val target = if (towardRight) overflow else 0f
            lastStep = now
            val distance = target - offset
            if (abs(distance) <= step) {
                offset = target
                towardRight = !towardRight
                lastStep = 0L
                postDelayed(this, edgeHold)
            } else {
                offset += if (distance > 0) step else -step
                postOnAnimation(this)
            }
            invalidate()
        }
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

    private companion object {
        const val appearTime = 150L
        const val edgeHold = 950L
    }
}

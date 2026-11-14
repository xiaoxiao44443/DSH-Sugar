package app.dsh.sugar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

internal enum class MobileIcon { PHONE, DISPLAYS, PREVIEW, STOP, FOLD, CANCEL, SETTINGS, CHAT, PLUS }

/** A single stroke weight for both the tiny controls and the folded task badge. */
internal class MobileControlIcon(private val icon: MobileIcon, color: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bubble = if (icon == MobileIcon.CHAT) Path().apply {
        moveTo(5f, 4f)
        lineTo(19f, 4f)
        quadTo(21f, 4f, 21f, 6f)
        lineTo(21f, 15f)
        quadTo(21f, 17f, 19f, 17f)
        lineTo(10f, 17f)
        lineTo(5f, 21f)
        lineTo(5f, 17f)
        quadTo(3f, 17f, 3f, 15f)
        lineTo(3f, 6f)
        quadTo(3f, 4f, 5f, 4f)
        close()
    } else null
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        val save = canvas.save()
        canvas.translate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2)
        canvas.scale(size / 24, size / 24)
        fun line(x1: Int, y1: Int, x2: Int, y2: Int) =
            canvas.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), pen)
        when (icon) {
            MobileIcon.PHONE -> {
                canvas.drawRoundRect(5.8f, 2.3f, 18.2f, 21.7f, 3f, 3f, pen)
                line(10, 18, 14, 18)
            }
            MobileIcon.DISPLAYS -> {
                // The two overlapping outlines span y=3.3..19.4 in this 24-unit viewport.
                canvas.translate(0f, .65f)
                canvas.drawRoundRect(2.5f, 3.3f, 17.8f, 15.2f, 2.1f, 2.1f, pen)
                line(7, 19, 19, 19)
                canvas.drawArc(17.6f, 15.4f, 21.6f, 19.4f, 0f, 90f, false, pen)
                line(22, 7, 22, 17)
            }
            MobileIcon.PREVIEW -> {
                canvas.drawRoundRect(2f, 4f, 22f, 17f, 2.3f, 2.3f, pen)
                line(12, 17, 12, 20); line(8, 20, 16, 20)
            }
            MobileIcon.STOP -> canvas.drawRoundRect(5f, 5f, 19f, 19f, 3f, 3f, pen)
            MobileIcon.FOLD -> line(5, 12, 19, 12)
            MobileIcon.CANCEL -> { line(6, 6, 18, 18); line(18, 6, 6, 18) }
            MobileIcon.PLUS -> { line(5, 12, 19, 12); line(12, 5, 12, 19) }
            MobileIcon.CHAT -> canvas.drawPath(checkNotNull(bubble), pen)
            MobileIcon.SETTINGS -> {
                line(3, 7, 9, 7); line(15, 7, 21, 7)
                canvas.drawCircle(12f, 7f, 2.7f, pen)
                line(3, 17, 6, 17); line(12, 17, 21, 17)
                canvas.drawCircle(9f, 17f, 2.7f, pen)
            }
        }
        canvas.restoreToCount(save)
    }
    fun setInk(color: Int) { pen.color = color; invalidateSelf() }
    override fun setAlpha(alpha: Int) { pen.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { pen.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

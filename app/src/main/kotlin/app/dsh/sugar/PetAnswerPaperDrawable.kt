package app.dsh.sugar

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** The stitched pocket journal carried by Whale Lullaby. All measurements below are in dp. */
internal class PetAnswerPaperDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val outline = Path()
    private val detail = Path()
    private var tintAlpha = 255
    private var paperWash: Shader? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width() / density
        val h = bounds.height() / density
        paperWash = if (w > 0 && h > 0) LinearGradient(6f, 0f, w, h,
            intArrayOf(0xFFFFFCF4.toInt(), 0xFFFFFAF2.toInt(), 0xFFF3E9E6.toInt()),
            floatArrayOf(0f, .63f, 1f), Shader.TileMode.CLAMP) else null
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width() / density
        val h = bounds.height() / density
        if (w < 10 || h < 10) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(density, density)

        // The cover and two page edges peek out beneath the flexible top sheet.
        canvas.drawRoundRect(.3f, 1.6f, w - .4f, h - .3f, 4.8f, 4.8f, fill(0xFF8F7D9E))
        canvas.drawRoundRect(.9f, 1.3f, w - .9f, h - 1.15f, 4.3f, 4.3f, fill(0xFFCEC3CD))
        canvas.drawRoundRect(1.1f, 1f, w - 1.2f, h - 2.0f, 4.3f, 4.3f, fill(0xFFFFF7E7))
        val right = w - 1.5f
        val bottom = h - 3f
        outline.rewind()
        outline.moveTo(3.6f, .75f)
        outline.quadTo(w * .48f, .15f, right - 6, .9f)
        outline.quadTo(right, 1.1f, right, 6.3f)
        outline.lineTo(right - .25f, bottom - 6)
        outline.quadTo(right - .1f, bottom, right - 6, bottom)
        outline.quadTo(w * .48f, bottom - .65f, 3.5f, bottom)
        outline.quadTo(.65f, bottom, .65f, bottom - 3f)
        outline.lineTo(.65f, 4f)
        outline.quadTo(.65f, 1f, 3.6f, .75f)
        outline.close()
        fill(0xFFFFFFFF)
        ink.shader = paperWash
        canvas.drawPath(outline, ink)
        canvas.drawPath(outline, line(0xFFB9A9B7, .75f))

        val pageClip = canvas.save()
        canvas.clipPath(outline)
        canvas.drawRect(.6f, .5f, 4.8f, bottom, fill(0xFFC2B2CC))
        canvas.drawRect(4.8f, .5f, 6.1f, bottom, fill(0xFFE6D8DF))
        canvas.drawLine(7.45f, 2.4f, 7.25f, bottom - 1, line(0xFFCFC0CE, .6f))
        var y = 6f
        while (y + 2.3f < bottom - 1.5f) {
            canvas.drawLine(1.85f, y + 2.25f, 3.2f, y, line(0xFFAB96BA, .85f))
            canvas.drawLine(2.3f, y + 2.3f, 3.55f, y + .3f, line(0xFFF4E9EA, .85f))
            y += 9.2f
        }
        canvas.restoreToCount(pageClip)

        // Compact sheets dedicate the entire footer to the two action glyphs.
        if (w >= 80f) drawFolioMark(canvas, bottom)
        canvas.restoreToCount(save)
    }

    private fun drawFolioMark(canvas: Canvas, bottom: Float) {
        // A foil crescent and the same four-point star as the speech bubble.
        detail.rewind()
        detail.moveTo(12.5f, bottom - 5.4f)
        detail.cubicTo(9.25f, bottom - 5.15f, 8.8f, bottom - 1.6f, 12.25f, bottom - 1.25f)
        detail.quadTo(14.2f, bottom - 1.03f, 15.05f, bottom - 2.6f)
        detail.cubicTo(12.9f, bottom - 1.75f, 10.75f, bottom - 3.45f, 12.5f, bottom - 5.4f)
        detail.close()
        canvas.drawPath(detail, fill(0xFFDFC889))
        canvas.drawPath(detail, line(0xFFB6A0B8, .4f))
        detail.rewind()
        detail.moveTo(16.8f, bottom - 5.2f)
        detail.quadTo(17.1f, bottom - 4.05f, 18f, bottom - 3.95f)
        detail.quadTo(17.0f, bottom - 3.6f, 16.8f, bottom - 2.65f)
        detail.quadTo(16.55f, bottom - 3.7f, 15.65f, bottom - 3.95f)
        detail.quadTo(16.55f, bottom - 4.2f, 16.8f, bottom - 5.2f)
        detail.close()
        canvas.drawPath(detail, fill(0xFFBAA6C7))
    }

    private fun fill(argb: Long): Paint = ink.apply {
        shader = null
        style = Paint.Style.FILL
        color = argb.toInt()
        alpha = tintAlpha
    }

    private fun line(argb: Long, width: Float): Paint = fill(argb).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    override fun setAlpha(alpha: Int) {
        tintAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        ink.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

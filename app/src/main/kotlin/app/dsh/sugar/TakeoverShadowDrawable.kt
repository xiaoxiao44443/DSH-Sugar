package app.dsh.sugar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A cached software shadow with an empty center, leaving the window's real blur visible. */
internal class TakeoverShadowDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    val sideInsetPx = (22f * density).roundToInt()
    val topInsetPx = (20f * density).roundToInt()
    val bottomInsetPx = (28f * density).roundToInt()
    private val defaultCornerRadius = 20f * density
    private val card = Rect()
    private var cornerRadius = defaultCornerRadius
    private var ambientColor = SugarGlassMaterial.withAlpha(0xff7162a3.toInt(), .14)
    private var contactColor = SugarGlassMaterial.withAlpha(0xff625784.toInt(), .07)
    private var cache: Bitmap? = null
    private var dirty = true
    private val output = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    /** Coordinates are local to this drawable's owning window; moving alone reuses the bitmap. */
    fun setCardBounds(bounds: Rect, cornerRadiusPx: Float = defaultCornerRadius) {
        val radius = cornerRadiusPx.coerceIn(0f, min(bounds.width(), bounds.height()).coerceAtLeast(0) / 2f)
        if (card == bounds && cornerRadius == radius) return
        if (card.width() != bounds.width() || card.height() != bounds.height() || cornerRadius != radius) dirty = true
        card.set(bounds)
        cornerRadius = radius
        invalidateSelf()
    }

    fun setPalette(material: SugarGlassMaterial) {
        val hue = SugarGlassMaterial.blend(material.shadow, material.accent(true), if (material.dark) .58f else .62f)
        val ambient = SugarGlassMaterial.withAlpha(hue, if (material.dark) .20 else .14)
        val contact = SugarGlassMaterial.withAlpha(material.shadow, if (material.dark) .12 else .07)
        if (ambientColor == ambient && contactColor == contact) return
        ambientColor = ambient
        contactColor = contact
        dirty = true
        invalidateSelf()
    }

    private fun rebuild() {
        dirty = false
        if (card.isEmpty) { cache = null; return }
        val width = card.width().toFloat() + 2f * sideInsetPx
        val height = card.height().toFloat() + topInsetPx + bottomInsetPx
        // Normally a small card. Bound the allocation even if given a full-window rectangle.
        val scale = minOf(1f, 2048f / maxOf(width, height), sqrt(2_097_152.0 / (width.toDouble() * height)).toFloat())
        val bitmap = Bitmap.createBitmap(ceil(width * scale).toInt().coerceAtLeast(1),
            ceil(height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val shape = RectF(sideInsetPx.toFloat(), topInsetPx.toFloat(),
            sideInsetPx + card.width().toFloat(), topInsetPx + card.height().toFloat())
        val pen = Paint(Paint.ANTI_ALIAS_FLAG)
        fun layer(color: Int, blurDp: Float, offsetDp: Float) {
            pen.color = color
            pen.maskFilter = BlurMaskFilter(blurDp * density, BlurMaskFilter.Blur.NORMAL)
            val save = canvas.save()
            canvas.translate(0f, offsetDp * density)
            canvas.drawRoundRect(shape, cornerRadius, cornerRadius, pen)
            canvas.restoreToCount(save)
        }
        layer(ambientColor, 15f, 3f)
        layer(contactColor, 6f, 1f)
        pen.maskFilter = null
        pen.color = Color.WHITE
        pen.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawRoundRect(shape, cornerRadius, cornerRadius, pen)
        // The previous bitmap can still be referenced by a hardware display list; never recycle it.
        cache = bitmap
    }

    override fun draw(canvas: Canvas) {
        if (card.isEmpty || bounds.isEmpty) return
        if (dirty) rebuild()
        val bitmap = cache ?: return
        destination.set((card.left - sideInsetPx).toFloat(), (card.top - topInsetPx).toFloat(),
            (card.right + sideInsetPx).toFloat(), (card.bottom + bottomInsetPx).toFloat())
        val save = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(bitmap, null, destination, output)
        canvas.restoreToCount(save)
    }

    override fun getOutline(outline: Outline) {
        if (card.isEmpty) outline.setEmpty() else outline.setRoundRect(card, cornerRadius)
    }

    override fun setAlpha(alpha: Int) { output.alpha = alpha; invalidateSelf() }
    override fun getAlpha() = output.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) { output.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

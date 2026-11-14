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
import kotlin.math.min

/** Paper stock, cut edges and printed details of the pet's pocket stationery. */
internal class PetPackPaperDrawable(context: Context, private val packId: String) : Drawable() {
    private enum class Paper(val light: Int, val shade: Int, val edge: Int, val trim: Int, val accent: Int) {
        LETTER(0xFFFCFDFF.toInt(), 0xFFE3ECF9.toInt(), 0xFF8B9EBB.toInt(), 0xFFB7CCE8.toInt(), 0xFF5976A5.toInt()),
        MENU(0xFFFFFCF2.toInt(), 0xFFF5EEDC.toInt(), 0xFFABA38A.toInt(), 0xFFD7CCAE.toInt(), 0xFF628FA0.toInt()),
        KNIT(0xFFFFFDF8.toInt(), 0xFFE6EEF2.toInt(), 0xFF94A9B6.toInt(), 0xFFC8DAE4.toInt(), 0xFF7299B0.toInt()),
        CHART(0xFF303D57.toInt(), 0xFF232E44.toInt(), 0xFF8391A5.toInt(), 0xFF687891.toInt(), 0xFFE4C98F.toInt()),
        GEM(0xFFF9FCFF.toInt(), 0xFFDFE8FB.toInt(), 0xFF8E9DBB.toInt(), 0xFFBBCBE8.toInt(), 0xFF607CAD.toInt()),
        POSTCARD(0xFFFFFEF5.toInt(), 0xFFE5F2F5.toInt(), 0xFF8CAAB1.toInt(), 0xFFAECDD6.toInt(), 0xFF568797.toInt()),
        MILK(0xFFFFFAE9.toInt(), 0xFFF7EACC.toInt(), 0xFFB9A784.toInt(), 0xFFE3D3AD.toInt(), 0xFF7D98B4.toInt()),
        TICKET(0xFFF1FAFF.toInt(), 0xFFD6E8F3.toInt(), 0xFF8CA9C3.toInt(), 0xFFAECADB.toInt(), 0xFF537C9E.toInt()),
        PRESS(0xFFFFFBEF.toInt(), 0xFFEDE7D8.toInt(), 0xFFA29D90.toInt(), 0xFFCBC5B6.toInt(), 0xFF58616B.toInt())
    }

    private val paper = when (packId) {
        "paopao01_tea" -> Paper.MENU
        "paopao02_winter" -> Paper.KNIT
        "paopao04_starlit" -> Paper.CHART
        "paopao05_sapphire" -> Paper.GEM
        "paopao06_sailor" -> Paper.POSTCARD
        "paopao07_cream" -> Paper.MILK
        "paopao09_rain" -> Paper.TICKET
        "paopao10_press" -> Paper.PRESS
        else -> Paper.LETTER
    }
    private val density = context.resources.displayMetrics.density
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sheet = Path()
    private val ornament = Path()
    private var wash: Shader? = null
    private var opacity = 255
    private var w = 0f
    private var h = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        w = bounds.width() / density
        h = bounds.height() / density
        sheet.rewind()
        if (w < 3 || h < 3) return
        wash = LinearGradient(0f, 0f, w, h, paper.light, paper.shade, Shader.TileMode.CLAMP)
        val l = .8f
        val t = .8f
        val r = w - .85f
        val b = h - 1.65f
        val cut = min(6f, min(r - l, b - t) / 5)
        when (paper) {
            Paper.MENU, Paper.CHART, Paper.GEM -> {
                val bevel = if (paper == Paper.GEM) cut else cut * .65f
                sheet.moveTo(l + bevel, t); sheet.lineTo(r - bevel, t); sheet.lineTo(r, t + bevel)
                sheet.lineTo(r, b - bevel); sheet.lineTo(r - bevel, b); sheet.lineTo(l + bevel, b)
                sheet.lineTo(l, b - bevel); sheet.lineTo(l, t + bevel); sheet.close()
            }
            Paper.TICKET -> {
                val mid = (t + b) / 2
                sheet.moveTo(l + cut, t); sheet.lineTo(r - cut, t); sheet.quadTo(r, t, r, t + cut)
                sheet.lineTo(r, mid - cut); sheet.cubicTo(r - cut, mid - cut, r - cut, mid + cut, r, mid + cut)
                sheet.lineTo(r, b - cut); sheet.quadTo(r, b, r - cut, b)
                sheet.lineTo(l + cut, b); sheet.quadTo(l, b, l, b - cut)
                sheet.lineTo(l, mid + cut); sheet.cubicTo(l + cut, mid + cut, l + cut, mid - cut, l, mid - cut)
                sheet.lineTo(l, t + cut); sheet.quadTo(l, t, l + cut, t); sheet.close()
            }
            Paper.MILK -> {
                // Rounded milk-label corners with a slight shoulder inside each corner.
                sheet.moveTo(l + cut * 1.6f, t); sheet.lineTo(r - cut * 1.6f, t)
                sheet.quadTo(r - cut, t, r - cut * .85f, t + cut * .42f); sheet.quadTo(r, t + cut * .5f, r, t + cut * 1.2f)
                sheet.lineTo(r, b - cut); sheet.quadTo(r, b, r - cut, b); sheet.lineTo(l + cut, b)
                sheet.quadTo(l, b, l, b - cut); sheet.lineTo(l, t + cut * 1.2f)
                sheet.quadTo(l, t + cut * .5f, l + cut * .85f, t + cut * .42f)
                sheet.quadTo(l + cut, t, l + cut * 1.6f, t); sheet.close()
            }
            Paper.PRESS -> {
                sheet.moveTo(l, t); sheet.lineTo(r - cut, t); sheet.lineTo(r, t + cut)
                sheet.lineTo(r, b); sheet.lineTo(l, b); sheet.close()
            }
            else -> {
                val radius = min(cut, when (paper) { Paper.KNIT -> 6f; Paper.POSTCARD -> 1.8f; else -> 4f })
                sheet.addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (sheet.isEmpty) return
        val outer = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(density, density)
        canvas.translate(0f, .8f)
        canvas.drawPath(sheet, fill(paper.edge))
        canvas.translate(0f, -.8f)
        fill(-1).shader = wash
        canvas.drawPath(sheet, pen)
        val decor = canvas.save()
        canvas.clipPath(sheet)
        if (w > 24 && h > 20) when (paper) {
            Paper.LETTER -> letter(canvas)
            Paper.MENU -> menu(canvas)
            Paper.KNIT -> knit(canvas)
            Paper.CHART -> chart(canvas)
            Paper.GEM -> jewel(canvas)
            Paper.POSTCARD -> postcard(canvas)
            Paper.MILK -> milk(canvas)
            Paper.TICKET -> ticket(canvas)
            Paper.PRESS -> newspaper(canvas)
        }
        canvas.restoreToCount(decor)
        canvas.drawPath(sheet, stroke(paper.edge, .75f))
        canvas.restoreToCount(outer)
    }

    private fun letter(c: Canvas) {
        // A scalloped maid collar along the binding, with eyelets punched in blue ribbon.
        c.drawRect(0f, 0f, 3.7f, h, fill(paper.accent))
        var y = 1.4f
        while (y < h + 4) {
            c.drawCircle(3.5f, y, 3.4f, fill(paper.trim))
            c.drawCircle(3.5f, y, 2.65f, fill(paper.light))
            c.drawCircle(3.7f, y, .67f, fill(paper.trim))
            y += 6.6f
        }
        if (w < 80) return
        val cy = h - 7.5f
        ornament.rewind(); ornament.moveTo(17f, cy)
        ornament.quadTo(10f, cy - 6f, 11.1f, cy + 1.4f); ornament.quadTo(12.1f, cy + 4f, 17f, cy)
        ornament.quadTo(23.4f, cy - 5.5f, 22.7f, cy + 1.4f); ornament.quadTo(22f, cy + 4f, 17f, cy)
        c.drawPath(ornament, stroke(paper.accent, .85f))
        c.drawCircle(17f, cy, 1.1f, fill(0xFFCDAE72.toInt()))
    }

    private fun menu(c: Canvas) {
        // Twin letterpress rules with an inlaid shell at the foot of the menu.
        c.drawLine(4f, 5f, 4f, h - 6f, stroke(paper.trim, .85f))
        c.drawLine(6.2f, 7f, 6.2f, h - 8f, stroke(paper.edge, .45f))
        c.drawLine(9f, 3.4f, w - 8f, 3.4f, stroke(paper.trim, .6f))
        if (w < 80) return
        val y = h - 4.8f
        ornament.rewind(); ornament.moveTo(17f, y); ornament.quadTo(12.5f, y - 1.8f, 11.3f, y - 5f)
        ornament.quadTo(11f, y - 7.8f, 13.8f, y - 7f); ornament.quadTo(14.5f, y - 9.8f, 17f, y - 8.3f)
        ornament.quadTo(19.6f, y - 9.7f, 20.2f, y - 7f); ornament.quadTo(23.3f, y - 7.8f, 22.7f, y - 4.9f)
        ornament.quadTo(21f, y - 1.8f, 17f, y); ornament.close()
        c.drawPath(ornament, fill(paper.light)); c.drawPath(ornament, stroke(paper.accent, .8f))
        c.drawLine(17f, y - 1.1f, 14f, y - 6.8f, stroke(paper.trim, .75f))
        c.drawLine(17f, y - 1.1f, 19.9f, y - 6.8f, pen)
        c.drawLine(17f, y - 1.1f, 17f, y - 7.4f, pen)
    }

    private fun knit(c: Canvas) {
        c.drawRect(0f, 0f, 6.9f, h, fill(paper.trim))
        var y = -1f
        while (y < h) {
            c.drawLine(1.4f, y, 3.7f, y + 2.1f, stroke(paper.light, 1.1f))
            c.drawLine(3.7f, y + 2.1f, 5.8f, y - .35f, pen)
            y += 4f
        }
        if (w < 80) return
        val cp = c.save()
        c.translate(16f, h - 8.8f)
        repeat(6) {
            c.drawLine(0f, 0f, 0f, -4.2f, stroke(paper.accent, .75f))
            c.drawLine(0f, -2.2f, -1.05f, -3.2f, pen)
            c.drawLine(0f, -2.2f, 1.05f, -3.2f, pen)
            c.rotate(60f)
        }
        c.restoreToCount(cp)
    }

    private fun chart(c: Canvas) {
        // Brass corner sights on a midnight sky map.
        c.drawLine(4.4f, 7.4f, 4.4f, h - 8f, stroke(paper.trim, .55f))
        c.drawLine(7f, 3.6f, 27f, 3.6f, stroke(paper.accent, .65f))
        c.drawLine(w - 16f, 3.6f, w - 6.7f, 3.6f, pen)
        var y = 12f
        while (y < h - 9) { c.drawLine(4.3f, y, 6.2f, y, stroke(paper.trim, .6f)); y += 8f }
        if (w < 80) return
        val b = h - 5f
        ornament.rewind(); ornament.moveTo(10f, b); ornament.lineTo(16f, b - 4.4f)
        ornament.lineTo(21.6f, b - 2.4f); ornament.lineTo(27f, b - 6.8f)
        c.drawPath(ornament, stroke(paper.trim, .65f))
        c.drawCircle(10f, b, .8f, fill(paper.accent)); c.drawCircle(16f, b - 4.4f, 1.1f, pen)
        c.drawCircle(21.6f, b - 2.4f, .75f, pen); c.drawCircle(27f, b - 6.8f, 1f, pen)
    }

    private fun jewel(c: Canvas) {
        ornament.rewind(); ornament.moveTo(0f, 0f); ornament.lineTo(9f, 0f)
        ornament.lineTo(2.4f, 7f); ornament.lineTo(5.6f, h * .43f); ornament.lineTo(1.8f, h - 8.3f)
        ornament.lineTo(10f, h); ornament.lineTo(0f, h); ornament.close()
        c.drawPath(ornament, fill(paper.trim))
        c.drawLine(2.4f, 7f, 5.6f, h * .43f, stroke(paper.light, .85f))
        c.drawLine(1.8f, h - 8.3f, 5.6f, h * .43f, stroke(paper.accent, .55f))
        c.drawLine(w - 10f, 1.8f, w - 2.3f, 9f, stroke(paper.trim, .8f))
        if (w < 80) return
        val y = h - 7f
        ornament.rewind(); ornament.moveTo(12f, y - 1f); ornament.lineTo(14f, y - 3.4f)
        ornament.lineTo(20.3f, y - 3.4f); ornament.lineTo(22.4f, y - 1f); ornament.lineTo(17.2f, y + 3f); ornament.close()
        c.drawPath(ornament, fill(paper.trim)); c.drawPath(ornament, stroke(paper.accent, .7f))
        c.drawLine(12.8f, y - 1f, 21.5f, y - 1f, stroke(paper.light, .65f))
    }

    private fun postcard(c: Canvas) {
        // Alternating airmail slugs and a postmark, kept outside the address area.
        var y = -6f
        var red = false
        while (y < h) {
            ornament.rewind(); ornament.moveTo(1.6f, y + 3f); ornament.lineTo(4.8f, y)
            ornament.lineTo(4.8f, y + 4.2f); ornament.lineTo(1.6f, y + 7.2f); ornament.close()
            c.drawPath(ornament, fill(if (red) 0xFFCB968B.toInt() else paper.accent))
            red = !red; y += 10f
        }
        if (w < 80) return
        val y0 = h - 9f
        c.drawCircle(13f, y0, 4f, stroke(paper.accent, .7f))
        c.drawLine(11.4f, y0, 14.7f, y0, pen)
        repeat(3) { n ->
            val y1 = y0 - 2.2f + n * 2.1f
            ornament.rewind(); ornament.moveTo(16f, y1); ornament.cubicTo(19f, y1 - 1.7f, 21f, y1 + 1.5f, 24f, y1 - .3f)
            c.drawPath(ornament, stroke(paper.accent, .55f))
        }
    }

    private fun milk(c: Canvas) {
        // Gingham selvedge on a butter-cream dairy label.
        c.drawRect(2f, .9f, 6.8f, h - 1.6f, fill(0xFFF1DCB6.toInt()))
        var y = 1f
        while (y < h) {
            c.drawRect(2f, y, 4.4f, y + 2.4f, fill(paper.trim))
            c.drawRect(4.4f, y + 2.4f, 6.8f, y + 4.8f, pen)
            y += 4.8f
        }
        if (w < 80) return
        val y0 = h - 7.5f
        ornament.rewind(); ornament.moveTo(10.8f, y0 + .8f)
        ornament.cubicTo(10.4f, y0 - 5f, 18.4f, y0 - 5.9f, 20.9f, y0 - .9f)
        ornament.quadTo(22.8f, y0, 24f, y0 - 3.4f); ornament.quadTo(25.7f, y0 - 2.1f, 24f, y0)
        ornament.quadTo(24.8f, y0 + 2f, 21.8f, y0 + 1.5f)
        ornament.cubicTo(18.8f, y0 + 4.2f, 12.2f, y0 + 4f, 10.8f, y0 + .8f); ornament.close()
        c.drawPath(ornament, fill(paper.accent)); c.drawCircle(13.4f, y0 - .7f, .65f, fill(paper.light))
    }

    private fun ticket(c: Canvas) {
        var y = 4.5f
        while (y < h - 5) { c.drawLine(6.7f, y, 6.7f, y + 1.7f, stroke(paper.edge, .75f)); y += 4.2f }
        c.drawLine(10f, 3.3f, w - 9f, 3.3f, stroke(0xFFFFFFFF.toInt(), .85f))
        if (w < 80) return
        val y0 = h - 6.5f
        ornament.rewind(); ornament.moveTo(16.5f, y0 - 7f)
        ornament.cubicTo(15.8f, y0 - 4f, 12.6f, y0 - 2.3f, 13.4f, y0)
        ornament.cubicTo(14.7f, y0 + 3.5f, 19.2f, y0 + 2.2f, 18.9f, y0 - .5f)
        ornament.quadTo(18.9f, y0 - 2f, 16.5f, y0 - 7f); ornament.close()
        c.drawPath(ornament, fill(paper.trim)); c.drawPath(ornament, stroke(paper.accent, .7f))
        c.drawLine(21.2f, y0 + 1.2f, 25.8f, y0 + 1.2f, stroke(paper.edge, .65f))
    }

    private fun newspaper(c: Canvas) {
        c.drawLine(2.5f, 1.8f, 2.5f, h - 2f, stroke(paper.trim, 1f))
        c.drawLine(5f, 4.5f, 5f, h - 4f, stroke(paper.edge, .45f))
        c.drawLine(7f, 2.8f, w - 8f, 2.8f, stroke(paper.accent, .8f))
        c.drawLine(7f, 4.6f, w - 9f, 4.6f, stroke(paper.trim, .5f))
        ornament.rewind(); ornament.moveTo(w - 7f, .8f); ornament.lineTo(w - 7f, 7.2f)
        ornament.lineTo(w - .9f, 7.2f); ornament.close(); c.drawPath(ornament, fill(paper.trim))
        if (w < 80) return
        val y = h - 9f
        c.drawRect(9.5f, y - 2.7f, 13.9f, y + 3f, fill(paper.accent))
        c.drawLine(15.7f, y - 2.2f, 26.2f, y - 2.2f, stroke(paper.accent, .85f))
        c.drawLine(15.7f, y + .1f, 26.2f, y + .1f, stroke(paper.edge, .55f))
        c.drawLine(15.7f, y + 2.3f, 22.5f, y + 2.3f, pen)
    }

    private fun fill(color: Int): Paint = pen.apply {
        shader = null
        style = Paint.Style.FILL
        this.color = color
        alpha = opacity
    }

    private fun stroke(color: Int, width: Float): Paint = fill(color).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        pen.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

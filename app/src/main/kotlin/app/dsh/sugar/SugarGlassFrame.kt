package app.dsh.sugar

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable

/** Keep the rounded GradientDrawable outline for the window blur and elevation shadow. */
internal class SugarGlassFrame(material: SugarGlassMaterial, fill: Int, radius: Float, density: Float) :
    GradientDrawable() {
    private val rim = material.frameWidthPx(density)
    private val shadowAlpha = minOf(fill ushr 24, material.frameEdge ushr 24) / 255f

    init {
        cornerRadius = radius
        setColor(fill)
        setStroke(rim, material.frameEdge)
    }

    override fun getOutline(outline: Outline) {
        super.getOutline(outline)
        // GradientDrawable otherwise drops the shadow when fill and stroke have different alphas.
        outline.alpha = shadowAlpha * alpha / 255f
    }
}

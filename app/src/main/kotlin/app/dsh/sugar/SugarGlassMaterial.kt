package app.dsh.sugar

import kotlin.math.roundToInt
import kotlin.math.pow

/** ARGB values for Sugar's frosted surfaces and the controls on them. */
internal class SugarGlassMaterial(private val surface: Int, val dark: Boolean) {
    val tint = if (dark) blend(surface, 0xff273044.toInt(), .10f) else blend(surface, 0xfffeffff.toInt(), .86f)
    val badgeEdge = withAlpha(0xffffffff.toInt(), if (dark) .18 else .25)
    val shadow = if (dark) 0xff121122.toInt() else 0xff49447c.toInt()

    private val pageLevel get() = luminance(surface)
    val modalDepth get() = 1.0 - smoothStep(.075, .62, pageLevel)
    val frameEdge: Int get() {
        val depth = modalDepth
        val slate = blend(surface, 0xffacb0b8.toInt(), .88f)
        return withAlpha(blend(0xffffffff.toInt(), slate, depth.toFloat()), .94 - .42 * depth)
    }
    fun frameWidthPx(density: Float) = ((1.50 - .42 * modalDepth) * density).roundToInt().coerceAtLeast(1)
    // Keep the page's hue, including lighter colored pages. Only a small neutral tint is added.
    val modalTint get() = blend(surface, blend(0xfffeffff.toInt(), 0xff273044.toInt(), modalDepth.toFloat()), .055f)
    val modalBlurDp get() = 22.0 - 4.0 * modalDepth

    fun modalFill(ink: Int, withBlur: Boolean, opacity: Double): Int {
        val p = opacity.coerceIn(0.0, 1.0)
        // Suppress text and control outlines underneath while still transmitting broad color changes.
        val lightAlpha = if (withBlur) .56 + .12 * p else .72 + .10 * p
        val darkAlpha = if (withBlur) .77 + .03 * p else .82 + .01 * p
        val floor = lightAlpha + (darkAlpha - lightAlpha) * modalDepth
        val base = modalTint
        // On white or pastel pages the translucent glass preserves the underlying colors.
        // Only deep pages need to protect light lettering against light inputs underneath.
        val protection = 1.0 - smoothStep(.095, .165, pageLevel)
        val foreground = luminance(ink)
        if (protection <= 0 || foreground < .5) return withAlpha(base, floor)
        val blue = accent()
        val violet = accent(true)
        val buttonLayers = listOf(buttonFill(blue, true, base) to blue, buttonFill(violet, false, base) to violet,
            buttonFill(blue, false, base) to blue)

        fun readable(background: Int) = (foreground + .05) / (luminance(background) + .05) >= 4.8
        fun passes(alpha: Int): Boolean {
            val panel = blend(-1, base, alpha / 255f)
            if (!readable(panel) || !readable(blend(panel, blue, .15f))) return false
            return buttonLayers.all { (layer, tone) ->
                val button = blend(panel, layer, (layer ushr 24) / 255f)
                readable(button) && readable(blend(button, tone, .19f))
            }
        }

        var lo = (255 * floor).roundToInt()
        var hi = 239
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (passes(mid)) hi = mid else lo = mid + 1
        }
        return withAlpha(base, floor + (lo / 255.0 - floor) * protection)
    }

    fun accent(violet: Boolean = false): Int = when {
        violet && dark -> 0xffc4bcff.toInt()
        violet -> 0xff6860ad.toInt()
        dark -> 0xffb6c9ff.toInt()
        else -> 0xff536ca9.toInt()
    }

    fun buttonFill(tone: Int, emphasized: Boolean, base: Int = tint): Int =
        withAlpha(blend(base, tone, if (emphasized) .15f else .105f), if (emphasized) .48 else .34)

    companion object {
        private val linear = DoubleArray(256) {
            val value = it / 255.0
            if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }

        private fun luminance(c: Int) =
            .2126 * linear[(c ushr 16) and 255] + .7152 * linear[(c ushr 8) and 255] + .0722 * linear[c and 255]

        private fun smoothStep(start: Double, end: Double, value: Double): Double {
            val t = ((value - start) / (end - start)).coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
        fun withAlpha(color: Int, opacity: Double): Int =
            (color and 0x00ffffff) or ((255 * opacity.coerceIn(0.0, 1.0)).roundToInt() shl 24)

        fun blend(from: Int, to: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val a = (from ushr shift) and 255
                val b = (to ushr shift) and 255
                return (a + (b - a) * amount).roundToInt() shl shift
            }
            return 0xff000000.toInt() or channel(16) or channel(8) or channel(0)
        }
    }
}

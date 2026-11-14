package app.dsh.sugar

import kotlin.math.pow
import kotlin.math.roundToInt

internal object SidebarBackdropColor {
    const val COLUMNS = 12
    const val ROWS = 40

    private val linearSrgb = DoubleArray(256) { byte ->
        val encoded = byte / 255.0
        if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
    }

    /** A row-major sRGB strip in screen order. PixelCopy has already composited the page. */
    fun representative(pixels: IntArray, railOnLeft: Boolean): Int {
        require(pixels.size == COLUMNS * ROWS)
        val first = pixels[0] and 0xffffff
        if (pixels.all { (it and 0xffffff) == first }) return first

        val columnWeights = DoubleArray(COLUMNS) { x ->
            val distance = if (railOnLeft) x else COLUMNS - 1 - x
            2.0 - distance.toDouble() / (COLUMNS - 1)
        }
        val lab = DoubleArray(pixels.size * 3)
        var lightnessSum = 0.0
        var aSum = 0.0
        var bSum = 0.0
        var squaredSum = 0.0
        for (i in pixels.indices) {
            val offset = i * 3
            writeOklab(pixels[i], lab, offset)
            val weight = columnWeights[i % COLUMNS]
            val lightness = lab[offset]
            val a = lab[offset + 1]
            val b = lab[offset + 2]
            lightnessSum += weight * lightness
            aSum += weight * a
            bSum += weight * b
            squaredSum += weight * (lightness * lightness + a * a + b * b)
        }
        val total = columnWeights.sum() * ROWS
        val centerL = lightnessSum / total
        val centerA = aSum / total
        val centerB = bSum / total
        val variance = squaredSum / total - (centerL * centerL + centerA * centerA + centerB * centerB)
        // Widen the Cauchy weighting on a diverse surface. Both halves of a two-tone
        // page keep contributing; small differences on a mostly uniform page are preserved.
        val scaleSquared = maxOf(0.08 * 0.08, variance)
        var robustWeight = 0.0
        lightnessSum = 0.0
        aSum = 0.0
        bSum = 0.0
        for (i in pixels.indices) {
            val offset = i * 3
            val dl = lab[offset] - centerL
            val da = lab[offset + 1] - centerA
            val db = lab[offset + 2] - centerB
            val distanceSquared = dl * dl + da * da + db * db
            val weight = columnWeights[i % COLUMNS] / (1.0 + distanceSquared / scaleSquared)
            robustWeight += weight
            lightnessSum += weight * lab[offset]
            aSum += weight * lab[offset + 1]
            bSum += weight * lab[offset + 2]
        }
        return toRgb(lightnessSum / robustWeight, aSum / robustWeight, bSum / robustWeight)
    }

    // Björn Ottosson's linear-sRGB / Oklab matrices. https://bottosson.github.io/posts/oklab/
    private fun writeOklab(color: Int, dest: DoubleArray, offset: Int) {
        val red = linearSrgb[color ushr 16 and 255]
        val green = linearSrgb[color ushr 8 and 255]
        val blue = linearSrgb[color and 255]
        val l = Math.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
        val m = Math.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
        val s = Math.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
        dest[offset] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        dest[offset + 1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        dest[offset + 2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    }

    private fun toRgb(lightness: Double, a: Double, b: Double): Int {
        val l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b)
        val m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b)
        val s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b)
        val red = encodedByte(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
        val green = encodedByte(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
        val blue = encodedByte(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
        return (red shl 16) or (green shl 8) or blue
    }

    private fun cube(value: Double) = value * value * value

    private fun encodedByte(linear: Double): Int {
        val bounded = linear.coerceIn(0.0, 1.0)
        val encoded = if (bounded <= 0.0031308) 12.92 * bounded else 1.055 * bounded.pow(1.0 / 2.4) - 0.055
        return (encoded * 255.0).roundToInt()
    }
}

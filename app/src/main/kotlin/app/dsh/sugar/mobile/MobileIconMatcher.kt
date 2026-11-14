package app.dsh.sugar.mobile

import kotlin.math.max
import kotlin.math.min

/** Fixed-size RGB matching. Transparent pixels are ignored; translucent pixels may have any backdrop. */
internal object MobileIconMatcher {
    const val MAX_ICON_EDGE = 192
    const val MAX_SCREEN_PIXELS = 4_194_304
    private const val MAX_COMPARISONS = 28_000_000

    data class Hit(val left: Int, val top: Int)
    data class Result(val hits: List<Hit>, val multiple: Boolean, val truncated: Boolean) {
        val status: String get() = when {
            multiple -> "multiple"
            truncated -> "incomplete"
            hits.isEmpty() -> "not_found"
            else -> "unique"
        }
    }

    private class Sample(val offset: Int, rgb: Int, private val tolerance: Int) {
        val alpha = rgb ushr 24
        val bucket = bucket(rgb)
        private fun low(value: Int) = max(0, (value * alpha) / 255 - tolerance)
        private fun high(value: Int) = min(255, (value * alpha + 254) / 255 + 255 - alpha + tolerance)
        private val lr = low(rgb ushr 16 and 255)
        private val lg = low(rgb ushr 8 and 255)
        private val lb = low(rgb and 255)
        private val hr = high(rgb ushr 16 and 255)
        private val hg = high(rgb ushr 8 and 255)
        private val hb = high(rgb and 255)
        fun accepts(rgb: Int): Boolean =
            (rgb ushr 16 and 255) in lr..hr && (rgb ushr 8 and 255) in lg..hg && (rgb and 255) in lb..hb
    }

    private fun bucket(rgb: Int) = (rgb ushr 12 and 0xf00) or (rgb ushr 8 and 0xf0) or (rgb ushr 4 and 0xf)

    fun find(screen: IntArray, width: Int, height: Int, icon: IntArray, iw: Int, ih: Int,
             tolerance: Int, maxMatches: Int): Result {
        require(width > 0 && height > 0 && width.toLong() * height in 1..MAX_SCREEN_PIXELS.toLong() &&
            screen.size == width * height) { "Invalid screenshot dimensions." }
        require(iw in 1..MAX_ICON_EDGE && ih in 1..MAX_ICON_EDGE && icon.size == iw * ih) { "Invalid icon dimensions." }
        require(tolerance in 0..64 && maxMatches in 1..32) { "Invalid match options." }
        val frequency = IntArray(4096)
        val stride = max(1, screen.size / 2048)
        for (i in screen.indices step stride) frequency[bucket(screen[i])]++
        // Check rare opaque colors from different parts of the icon before comparing all its pixels.
        val anchors = arrayOfNulls<Sample>(4)
        val ranks = IntArray(4) { Int.MAX_VALUE }
        val mask = ArrayList<Sample>()
        for (y in 0 until ih) for (x in 0 until iw) {
            val color = icon[y * iw + x]
            if (color ushr 24 == 0) continue
            val sample = Sample(y * width + x,color,tolerance)
            mask.add(sample)
            val quadrant = (if (y >= ih / 2) 2 else 0) + (if (x >= iw / 2) 1 else 0)
            val rank = (255 - sample.alpha) * 4096 + frequency[sample.bucket]
            if (rank < ranks[quadrant]) { ranks[quadrant] = rank; anchors[quadrant] = sample }
        }
        require(mask.isNotEmpty()) { "The template is entirely transparent." }
        if (iw > width || ih > height) return Result(emptyList(), false, false)
        val first = anchors.filterNotNull().sortedBy { (255 - it.alpha) * 4096 + frequency[it.bucket] }
        val ordered = (first + mask.filter { it !in first }).toTypedArray()
        val hits = ArrayList<Hit>()
        var comparisons = 0
        for (top in 0..height-ih) {
            candidate@ for (left in 0..width-iw) {
                val base = top * width + left
                for (sample in ordered) {
                    if (++comparisons > MAX_COMPARISONS) return Result(hits, hits.size > 1, true)
                    if (!sample.accepts(screen[base + sample.offset])) continue@candidate
                }
                if (hits.size == maxMatches) return Result(hits, true, true)
                hits.add(Hit(left,top))
            }
        }
        return Result(hits, hits.size > 1, false)
    }
}

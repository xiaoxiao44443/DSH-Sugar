package app.dsh.sugar.mobile

import org.json.JSONArray
import org.json.JSONObject

/** Reads small regions of the screenshot that already belongs to the selected task. */
internal object MobileColors {
    const val MAX_POINTS = 64
    const val MAX_RADIUS = 8

    @Synchronized fun read(image: JSONObject, args: JSONObject): JSONObject {
        val width = image.getInt("width")
        val height = image.getInt("height")
        val radius = if (args.has("radius")) nonnegativeInt(args.opt("radius")) else 0
        val samples = args.optJSONArray("points")
        if (radius == null || radius > MAX_RADIUS || samples == null || samples.length() !in 1..MAX_POINTS)
            return screenError("invalid_argument", "Supply 1–64 image points and an integer radius from 0 to 8.")
        val points = ArrayList<Pair<Int,Int>>(samples.length())
        for (i in 0 until samples.length()) {
            val point = samples.optJSONArray(i)
            val x = nonnegativeInt(point?.opt(0))
            val y = nonnegativeInt(point?.opt(1))
            if (point?.length() != 2 || x == null || y == null || x >= width || y >= height)
                return screenError("invalid_argument", "Each point must be an integer x,y inside the observation image.")
            points.add(x to y)
        }
        return try {
            val colors = withMobilePng(image.getString("data")) { bitmap ->
                require(bitmap.width == width && bitmap.height == height) { "Screenshot geometry changed." }
                sample(width, height, points, radius) { left, top, w, h, buffer ->
                    bitmap.getPixels(buffer, 0, w, left, top, w, h)
                }
            }
            val result = JSONArray()
            for (color in colors) result.put(JSONArray().put(color ushr 16).put((color ushr 8) and 255).put(color and 255))
            JSONObject().put("ok", true).put("colors", result)
        } catch (e: Exception) {
            screenError("image_unavailable", e.message ?: "Could not read colors from the observation.")
        }
    }

    private fun nonnegativeInt(value: Any?): Int? = when (value) {
        is Int -> value.takeIf { it >= 0 }
        is Long -> value.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }

    /** Average sRGB channels independently, rounding to the nearest byte, with edge clipping. */
    internal fun sample(width: Int, height: Int, points: List<Pair<Int,Int>>, radius: Int,
                        read: (left: Int, top: Int, w: Int, h: Int, buffer: IntArray) -> Unit): List<Int> {
        val edge = radius * 2 + 1
        val buffer = IntArray(edge * edge)
        return points.map { (x, y) ->
            val left = maxOf(0, x - radius)
            val top = maxOf(0, y - radius)
            val w = minOf(width - 1, x + radius) - left + 1
            val h = minOf(height - 1, y + radius) - top + 1
            read(left, top, w, h, buffer)
            val count = w * h
            var red = 0
            var green = 0
            var blue = 0
            for (i in 0 until count) {
                val pixel = buffer[i]
                red += (pixel ushr 16) and 255
                green += (pixel ushr 8) and 255
                blue += pixel and 255
            }
            val r = (red + count / 2) / count
            val g = (green + count / 2) / count
            val b = (blue + count / 2) / count
            (r shl 16) or (g shl 8) or b
        }
    }
}

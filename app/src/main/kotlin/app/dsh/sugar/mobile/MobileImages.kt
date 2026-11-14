package app.dsh.sugar.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Crop in the current rotated display's coordinates; the drivers already supply upright frames. */
internal object MobileImages {
    data class Mapping(
        val observationId: String, val width: Int, val height: Int,
        val offsetX: Double, val offsetY: Double, val scaleX: Double, val scaleY: Double,
    ) {
        fun displayPoint(x: Double, y: Double): Pair<Double, Double> {
            if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height)
                throw ScreenProblem("invalid_argument", "The target lies outside the observation image.")
            return (offsetX + x * scaleX) to (offsetY + y * scaleY)
        }
    }

    fun cropBounds(args: JSONObject, width: Int, height: Int, scaleX: Double, scaleY: Double): Rect {
        if (width < 1 || height < 1 || !scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0 || scaleY <= 0)
            invalid("Invalid source image geometry.")
        var left = 0
        var top = 0
        var right = width
        var bottom = height
        if (args.has("crop")) {
            val rect = args.optJSONObject("crop") ?: invalid("crop must contain left, top, right and bottom.")
            // Round inward when the original capture is already downscaled.
            left = ceil(number(rect, "left") / scaleX).toInt().coerceIn(0, width)
            top = ceil(number(rect, "top") / scaleY).toInt().coerceIn(0, height)
            right = floor(number(rect, "right") / scaleX).toInt().coerceIn(0, width)
            bottom = floor(number(rect, "bottom") / scaleY).toInt().coerceIn(0, height)
            if (left >= right || top >= bottom) invalid("The requested crop contains no visible image pixels.")
        }
        return Rect(left, top, right, bottom)
    }

    fun requestedWidth(args: JSONObject, croppedWidth: Int): Int {
        val requestedWidth = if (args.has("max_width")) number(args, "max_width") else croppedWidth.toDouble()
        if (requestedWidth < 1 || requestedWidth != floor(requestedWidth)) invalid("max_width must be a positive integer.")
        return minOf(croppedWidth, requestedWidth.toInt())
    }

    fun prepare(observation: JSONObject, args: JSONObject): Mapping? {
        if (!observation.optBoolean("ok", true)) return null
        val image = observation.optJSONObject("image")
        if (image == null) {
            if (args.has("crop") || args.has("max_width")) invalid("Cropping requires a screenshot.")
            return null
        }
        val width = image.getInt("width")
        val height = image.getInt("height")
        val scaleX = image.getDouble("scale_x")
        val scaleY = image.getDouble("scale_y")
        if (image.remove("source_geometry_applied") == true) {
            return Mapping(observation.getString("observation_id"), width, height,
                image.getDouble("offset_x"), image.getDouble("offset_y"), scaleX, scaleY)
        }
        val crop = cropBounds(args, width, height, scaleX, scaleY)
        val left = crop.left
        val top = crop.top
        val right = crop.right
        val bottom = crop.bottom
        val croppedWidth = crop.width()
        val croppedHeight = crop.height()
        val outputWidth = requestedWidth(args, croppedWidth)
        val outputHeight = (croppedHeight.toDouble() * outputWidth / croppedWidth).roundToInt().coerceAtLeast(1)
        val mapping = Mapping(observation.getString("observation_id"), outputWidth, outputHeight,
            left * scaleX, top * scaleY, croppedWidth * scaleX / outputWidth, croppedHeight * scaleY / outputHeight)
        val transformed = JSONObject().apply { image.keys().forEach { key -> put(key, image.get(key)) } }
        if (left != 0 || top != 0 || right != width || bottom != height || outputWidth != croppedWidth) {
            if (width.toLong() * height > 16_777_216L) invalid("Source image is too large to transform.")
            val png = Base64.decode(image.getString("data"), Base64.DEFAULT)
            val source = BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: throw ScreenProblem("image_unavailable", "Could not decode the screen capture.")
            var cropped: Bitmap? = null
            var resized: Bitmap? = null
            try {
                if (source.width != width || source.height != height) invalid("Captured image dimensions changed.")
                val clip = Bitmap.createBitmap(source, left, top, croppedWidth, croppedHeight)
                cropped = clip
                val frame = if (clip.width == outputWidth && clip.height == outputHeight) clip
                    else Bitmap.createScaledBitmap(clip, outputWidth, outputHeight, true)
                resized = frame
                val out = ByteArrayOutputStream()
                if (!frame.compress(Bitmap.CompressFormat.PNG, 100, out))
                    throw ScreenProblem("image_unavailable", "Could not encode the cropped screenshot.")
                transformed.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            } finally {
                if (resized !== cropped && resized !== source) resized?.recycle()
                if (cropped !== source) cropped?.recycle()
                source.recycle()
            }
        }
        transformed.put("width", mapping.width).put("height", mapping.height)
            .put("offset_x", mapping.offsetX).put("offset_y", mapping.offsetY)
            .put("scale_x", mapping.scaleX).put("scale_y", mapping.scaleY)
        observation.put("image", transformed)
        return mapping
    }

    fun forAction(args: JSONObject, mapping: Mapping?): JSONObject {
        if (args.optString("space") != "image") return args
        if (mapping == null || mapping.observationId != args.optString("observation_id"))
            throw ScreenProblem("stale_observation", "Read the target screen image before using image coordinates.")
        val result = JSONObject().apply { args.keys().forEach { key -> put(key, args.get(key)) } }
        fun mapPoint(xKey: String, yKey: String) {
            val (x, y) = mapping.displayPoint(number(args, xKey), number(args, yKey))
            result.put(xKey, x).put(yKey, y)
        }
        mapPoint("x", "y")
        if (args.has("x2") || args.has("y2")) mapPoint("x2", "y2")
        return result.put("space", "display")
    }

    private fun number(data: JSONObject, key: String): Double =
        (data.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: invalid("Expected numeric $key.")

    private fun invalid(message: String): Nothing = throw ScreenProblem("invalid_argument", message)
}

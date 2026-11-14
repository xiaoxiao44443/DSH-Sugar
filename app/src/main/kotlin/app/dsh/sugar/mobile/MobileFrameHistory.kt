package app.dsh.sugar.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** A bounded, memory-only history of a single task's screen. Historical frames are never action targets. */
internal class MobileFrameHistory {
    companion object {
        const val LONG_EDGE = 960
        const val QUALITY = 75
        const val RETENTION_MS = 60_000L
        const val MAX_FRAMES = 60
        const val MAX_BYTES = 6 * 1024 * 1024
        private const val MAX_REPLY_BYTES = 2 * 1024 * 1024
        private val expiryExecutor = ScheduledThreadPoolExecutor(1) { job ->
            Thread(job, "sugar-frame-expiry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }

    private class Frame(val id: Long, val elapsed: Long, val timestamp: Long, val data: ByteArray,
                        val geometry: JSONObject, val metadata: JSONObject)
    private data class Encoded(val data: ByteArray, val width: Int, val height: Int)

    private val frames = ArrayDeque<Frame>()
    private var bytes = 0
    private var nextId = 1L
    private var closed = false
    private var expiry: ScheduledFuture<*>? = null

    @Synchronized fun close() {
        closed = true
        expiry?.cancel(false)
        expiry = null
        frames.clear()
        bytes = 0
    }

    /** current is a non-blocking generation check; a paused or restarted sampler may finish an older encode. */
    fun add(observation: JSONObject, current: () -> Boolean = { true }) {
        if (!observation.optBoolean("ok", true)) return
        val image = observation.optJSONObject("image") ?: return
        val elapsed = image.getLong("captured_elapsed_ms")
        synchronized(this) { if (!current() || !eligible(elapsed)) return }
        val width = image.getInt("width")
        val height = image.getInt("height")
        val encoded = thumbnail(image, width, height)
        val geometry = JSONObject().put("width", encoded.width).put("height", encoded.height).put("mime_type", "image/webp")
            .put("scale_x", image.optDouble("scale_x", 1.0) * width / encoded.width)
            .put("scale_y", image.optDouble("scale_y", 1.0) * height / encoded.height)
            .put("offset_x", image.optDouble("offset_x", 0.0)).put("offset_y", image.optDouble("offset_y", 0.0))
        val metadata = JSONObject()
        for (key in listOf("display", "display_id", "package", "rotation", "width", "height", "window_id")) {
            if (observation.has(key)) metadata.put(key, observation.get(key))
        }
        synchronized(this) {
            if (!current() || !eligible(elapsed) || encoded.data.size > MAX_REPLY_BYTES) return
            prune()
            frames.add(Frame(nextId++, elapsed, image.getLong("timestamp_ms"), encoded.data, geometry, metadata))
            bytes += encoded.data.size
            while (bytes > MAX_BYTES || frames.size > MAX_FRAMES) removeOldest()
            if (expiry == null) scheduleExpiry()
        }
    }

    // A peek at an unchanged virtual display can return the previous producer frame.
    private fun eligible(elapsed: Long): Boolean = !closed && elapsed > (frames.peekLast()?.elapsed ?: -1) &&
        SystemClock.elapsedRealtime() - elapsed in 0 until RETENTION_MS

    private fun removeOldest() { bytes -= frames.removeFirst().data.size }

    private fun prune() {
        val cutoff = SystemClock.elapsedRealtime() - RETENTION_MS
        while (frames.isNotEmpty() && frames.first.elapsed <= cutoff) removeOldest()
    }

    private fun scheduleExpiry() {
        val first = frames.peekFirst() ?: return
        val delay = (first.elapsed + RETENTION_MS - SystemClock.elapsedRealtime()).coerceAtLeast(1)
        expiry = expiryExecutor.schedule({
            synchronized(this) {
                expiry = null
                prune()
                if (!closed) scheduleExpiry()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    @Suppress("DEPRECATION")
    private fun encode(bitmap: Bitmap): Encoded {
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(format, QUALITY, stream)) throw ScreenProblem("image_unavailable", "Could not compress the history frame.")
        return Encoded(stream.toByteArray(), bitmap.width, bitmap.height)
    }

    private fun thumbnail(image: JSONObject, width: Int, height: Int): Encoded {
        val data = Base64.decode(image.getString("data"), Base64.DEFAULT)
        if (image.getString("mime_type") == "image/webp" && maxOf(width, height) <= LONG_EDGE)
            return Encoded(data, width, height)
        val source = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw ScreenProblem("image_unavailable", "Could not read the history frame.")
        var frame = source
        try {
            if (width != source.width || height != source.height) throw ScreenProblem("image_unavailable", "Unexpected frame dimensions.")
            val scale = LONG_EDGE.toDouble() / maxOf(width, height)
            if (scale < 1) frame = Bitmap.createScaledBitmap(source, (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1), true)
            return encode(frame)
        } finally {
            if (frame !== source) frame.recycle()
            source.recycle()
        }
    }

    fun read(args: JSONObject): JSONObject {
        val since = integer(args, "since_ms", RETENTION_MS, 0L..RETENTION_MS)
        val limit = integer(args, "limit", 6, 1L..12L).toInt()
        val id = if (args.has("frame_id")) integer(args, "frame_id", 0, 1L..Long.MAX_VALUE) else null
        val now: Long
        val selected: List<Frame>
        synchronized(this) {
            if (closed) throw ScreenProblem("session_closed", "This screen history has ended.")
            prune()
            now = SystemClock.elapsedRealtime()
            val matches = frames.filter { now - it.elapsed <= since && (id == null || it.id == id) }
            if (id != null && matches.isEmpty()) throw ScreenProblem("frame_unavailable", "The requested frame is no longer in history.")
            // Include both ends of the requested interval when reducing a timeline to a few representative frames.
            selected = when {
                matches.size <= limit -> matches
                limit == 1 -> listOf(matches.last())
                else -> List(limit) { index -> matches[(index.toDouble() * matches.lastIndex / (limit - 1)).roundToInt()] }
            }
        }
        val sheet = args.optBoolean("sheet", false)
        val includeImages = args.optBoolean("include_images", false) && !sheet
        val entries = JSONArray()
        val reply = JSONObject().put("ok", true).put("frames", entries)
        var responseBytes = 0
        for (frame in selected) {
            val item = JSONObject(frame.metadata.toString()).put("frame_id", frame.id)
                .put("captured_elapsed_ms", frame.elapsed).put("timestamp_ms", frame.timestamp).put("age_ms", (now - frame.elapsed).coerceAtLeast(0L))
            val image = JSONObject(frame.geometry.toString())
            if (includeImages && responseBytes + frame.data.size <= MAX_REPLY_BYTES) {
                image.put("data", Base64.encodeToString(frame.data, Base64.NO_WRAP))
                responseBytes += frame.data.size
            } else if (includeImages) item.put("image_omitted", "reply_byte_limit")
            item.put("image", image)
            entries.put(item)
        }
        if (sheet && selected.isNotEmpty()) reply.put("image", contactSheet(selected, entries))
        synchronized(this) { if (closed) throw ScreenProblem("session_closed", "This screen history has ended.") }
        return reply
    }

    private fun integer(args: JSONObject, key: String, default: Long, range: LongRange): Long {
        if (!args.has(key)) return default
        val number = args.opt(key) as? Number ?: throw ScreenProblem("invalid_argument", "Expected numeric $key.")
        val integer = number.toLong()
        if (!number.toDouble().isFinite() || number.toDouble() != integer.toDouble() || integer !in range)
            throw ScreenProblem("invalid_argument", "Invalid $key.")
        return integer
    }

    private fun contactSheet(selected: List<Frame>, entries: JSONArray): JSONObject {
        val columns = minOf(3, selected.size)
        val cell = 320
        val label = 24
        val canvasBitmap = Bitmap.createBitmap(cell * columns, (cell + label) * ((selected.size + columns - 1) / columns),
            Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.rgb(28, 30, 34))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = Color.WHITE
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            selected.forEachIndexed { index, frame ->
                val bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                    ?: throw ScreenProblem("image_unavailable", "Could not read the stored thumbnail.")
                try {
                    val x = (index % columns) * cell
                    val y = (index / columns) * (cell + label)
                    val scale = cell.toDouble() / maxOf(bitmap.width, bitmap.height)
                    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                    val left = x + (cell - width) / 2
                    val top = y + (cell - height) / 2
                    val destination = Rect(left, top, left + width, top + height)
                    canvas.drawBitmap(bitmap, null, destination, paint)
                    val entry = entries.getJSONObject(index)
                    val caption = "${index + 1}/${selected.size}   #${frame.id}   ${entry.getLong("age_ms")} ms ago"
                    canvas.drawText(caption, (x + 6).toFloat(), (y + cell + 18).toFloat(), paint)
                    entry.put("tile_rect", JSONObject().put("left", left).put("top", top)
                        .put("right", left + width).put("bottom", top + height))
                } finally { bitmap.recycle() }
            }
            val sheet = encode(canvasBitmap)
            return JSONObject().put("mime_type", "image/webp").put("width", sheet.width).put("height", sheet.height)
                .put("data", Base64.encodeToString(sheet.data, Base64.NO_WRAP))
        } finally { canvasBitmap.recycle() }
    }
}

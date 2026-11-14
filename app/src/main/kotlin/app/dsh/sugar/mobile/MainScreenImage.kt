package app.dsh.sugar.mobile

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt

@TargetApi(30)
internal object MainScreenImage {
    fun capture(service: AccessibilityService, window: MainWindow, validateWindow: () -> Unit, args: JSONObject,
                historyPreview: Boolean = false): JSONObject {
        val pending = CompletableFuture<AccessibilityService.ScreenshotResult>()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                if (!pending.complete(screenshot)) screenshot.hardwareBuffer.close()
            }

            override fun onFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshot_rate_limited"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure_window"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "permission_denied"
                    else -> "screenshot_unavailable"
                }
                pending.completeExceptionally(ScreenProblem(reason, "Android could not capture the main display (code $errorCode)."))
            }
        })
        val result = try { pending.get(5, TimeUnit.SECONDS) } catch (e: ExecutionException) {
            throw e.cause as? ScreenProblem ?: e
        } catch (e: Exception) {
            // If the callback won this race, this caller owns the hardware buffer.
            if (!pending.completeExceptionally(e)) {
                pending.handle { value, _ -> value?.hardwareBuffer?.close() }
            }
            if (e is InterruptedException) Thread.currentThread().interrupt()
            if (e is TimeoutException) throw ScreenProblem("screenshot_timeout", "Timed out waiting for the main display.")
            throw e
        }
        val elapsedAt = SystemClock.elapsedRealtime() - (SystemClock.uptimeMillis() - result.timestamp).coerceAtLeast(0)
        val capturedAt = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedAt)
        val software = result.hardwareBuffer.use { buffer ->
            // The frame already exists. Validate it now; PNG encoding can outlive a valid frame.
            validateWindow()
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: throw ScreenProblem("screenshot_unavailable", "Android could not map the screen buffer.")
            try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
        } ?: throw ScreenProblem("screenshot_unavailable", "Android could not read the screen pixels.")

        return encodeOwnedFrame(software, window.width, window.height, args, historyPreview, result.timestamp, elapsedAt, capturedAt)
    }

    internal fun encodeOwnedFrame(software: Bitmap, displayWidth: Int, displayHeight: Int, args: JSONObject,
                                  historyPreview: Boolean, uptimeAt: Long, elapsedAt: Long, capturedAt: Long): JSONObject {
        var frame = software
        try {
            val sourceScaleX = displayWidth.toDouble() / frame.width
            val sourceScaleY = displayHeight.toDouble() / frame.height
            val rect = MobileImages.cropBounds(args, frame.width, frame.height, sourceScaleX, sourceScaleY)
            val selectedWidth = MobileImages.requestedWidth(args, rect.width())
            val clip = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
            if (frame !== clip) frame.recycle()
            frame = clip
            var limit = if (historyPreview) MobileFrameHistory.LONG_EDGE else 1600
            val requestedExtent = (maxOf(rect.width(), rect.height()).toDouble() * selectedWidth / rect.width()).roundToInt()
            limit = minOf(limit, requestedExtent)
            while (true) {
                val extent = maxOf(frame.width, frame.height)
                if (extent > limit) {
                    val x = maxOf(1, (frame.width.toDouble() * limit / extent).roundToInt())
                    val y = maxOf(1, (frame.height.toDouble() * limit / extent).roundToInt())
                    val resized = Bitmap.createScaledBitmap(frame, x, y, true)
                    if (resized !== frame) frame.recycle()
                    frame = resized
                }
                val stream = ByteArrayOutputStream()
                val format = if (historyPreview) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.PNG
                val quality = if (historyPreview) MobileFrameHistory.QUALITY else 100
                if (!frame.compress(format, quality, stream))
                    throw ScreenProblem("screenshot_unavailable", "Failed to encode the screen image.")
                if (stream.size() <= 2 * 1024 * 1024) {
                    val feedbackAfter = args.optLong("feedback_after_uptime_ms", -1)
                    if (feedbackAfter >= 0 && uptimeAt < feedbackAfter)
                        throw ScreenProblem("feedback_unavailable", "The screen capture predates the action.")
                    return JSONObject().put("mime_type", if (historyPreview) "image/webp" else "image/png")
                        .put("width", frame.width).put("height", frame.height)
                        .apply { if (feedbackAfter >= 0) put("feedback_delay_ms", uptimeAt - feedbackAfter) }
                        .apply { if (args.has("crop") || args.has("max_width")) put("source_geometry_applied", true) }
                        .put("offset_x", rect.left * sourceScaleX).put("offset_y", rect.top * sourceScaleY)
                        .put("scale_x", rect.width() * sourceScaleX / frame.width).put("scale_y", rect.height() * sourceScaleY / frame.height)
                        .put("captured_elapsed_ms", elapsedAt).put("timestamp_ms", capturedAt)
                        .put("data", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
                }
                limit = maxOf(1, maxOf(frame.width, frame.height) * 3 / 4)
            }
        } finally { frame.recycle() }
    }
}

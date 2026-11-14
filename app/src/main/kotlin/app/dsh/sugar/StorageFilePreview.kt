package app.dsh.sugar

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.text.Layout
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import app.dsh.sugar.runtime.GuestImage
import app.dsh.sugar.runtime.ProjectBridge
import app.dsh.sugar.runtime.SharedDshHome
import app.dsh.sugar.workspace.PrivateWorkspaceTree
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Only small, sampled thumbnails cross the settings bridge. Originals stay behind a read-only descriptor. */
internal object StorageFilePreview {
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val thumbnails = object : LruCache<String, String>(1024 * 1024) {
        override fun sizeOf(key: String, value: String) = value.length
    }

    private fun attachmentTree(bridge: ProjectBridge) = PrivateWorkspaceTree.control(
        bridge.context, GuestImage.root(bridge.context, bridge.controlId).toFile())

    private fun attachmentPath(relative: String): String {
        val parts = relative.split('/')
        require(parts.size >= 3 && hashPattern.matches(parts[2]) && parts[1] == parts[2].take(2))
        require((parts[0] in setOf("objects", "file-objects") && parts.size == 3) ||
            (parts[0] == "files" && parts.size == 4 && parts[3] == artifactName(parts[3])))
        return SharedDshHome.PATH.removePrefix("/") + "/attachments/v1/" + relative
    }

    private fun image(metadata: JSONObject, type: String) =
        metadata.optString("kind") == "image" || type.startsWith("image/")

    private fun decode(fd: ParcelFileDescriptor, maxEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(File("/proc/self/fd/${fd.fd}"))
        return ImageDecoder.decodeBitmap(source) { decoder, header, _ ->
            val width = header.size.width
            val height = header.size.height
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
            decoder.setTargetSize(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun decorate(bridge: ProjectBridge, files: JSONArray?) {
        if (files == null) return
        val pictures = (0 until files.length()).mapNotNull { index ->
            val row = files.optJSONObject(index) ?: return@mapNotNull null
            val relative = row.remove("relativePath") as? String ?: return@mapNotNull null
            val type = artifactMediaType(row.optString("name"), row.optString("mediaType"))
            if (index < 24 && image(row, type)) row to relative else null
        }
        if (pictures.isEmpty()) return
        runCatching {
            attachmentTree(bridge).use { tree ->
                for ((row, relative) in pictures) {
                    val data = runCatching {
                        val path = attachmentPath(relative)
                        val key = "${bridge.controlId}:${relative.split('/')[2]}:${row.optLong("bytes")}"
                        thumbnails.get(key) ?: tree.readOnlyDescriptor(path).use { fd ->
                            val bitmap = decode(fd, 112)
                            try {
                                val bytes = ByteArrayOutputStream()
                                @Suppress("DEPRECATION")
                                check(bitmap.compress(Bitmap.CompressFormat.WEBP, 77, bytes))
                                "data:image/webp;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                            } finally { bitmap.recycle() }
                        }.also { thumbnails.put(key, it) }
                    }.getOrNull()
                    if (data != null) row.put("thumbnail", data)
                }
            }
        }
    }

    interface Prepared {
        fun show(activity: Activity): AlertDialog?
        fun discard() {}
    }

    private class Picture(private val name: String, private val bitmap: Bitmap) : Prepared {
        override fun show(activity: Activity): AlertDialog {
            val frame = FrameLayout(activity)
            val picture = ZoomPicture(activity, bitmap)
            frame.addView(picture, FrameLayout.LayoutParams(MATCH_PARENT, (SugarDialog.previewSpace(activity).height * .58).roundToInt()))
            return SugarDialog.builder(activity).setTitle(name).setView(frame)
                .setNegativeButton(activity.sugarString(R.string.mobile_manager_close), null).show()
        }
        override fun discard() { bitmap.recycle() }
    }

    private class PlainText(private val name: String, private val content: String) : Prepared {
        override fun show(activity: Activity): AlertDialog {
            val maxHeight = (SugarDialog.previewSpace(activity).height * .62f).roundToInt().coerceAtLeast(1)
            val scroll = object : ScrollView(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight
                        else minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeight)
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
                }
            }
            val inset = (20 * activity.resources.displayMetrics.density).roundToInt()
            val body = TextView(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 14f
                breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                setPadding(inset, inset / 2, inset, inset / 2)
                setTextIsSelectable(true)
                text = content.ifEmpty { activity.sugarString(R.string.storage_preview_empty) }
            }
            scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return SugarDialog.builder(activity).setTitle(name).setView(scroll)
                .setNegativeButton(activity.sugarString(R.string.mobile_manager_close), null).show()
        }
    }

    private fun text(name: String, mime: String, rawType: String?, reportedBytes: Long,
                     open: () -> ParcelFileDescriptor?): Prepared? {
        if (reportedBytes > StoragePreviewText.LIMIT || !StoragePreviewText.candidate(name, mime)) return null
        val fd = open() ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            if (fd.statSize > StoragePreviewText.LIMIT) return@use null
            StoragePreviewText.read(input, rawType)?.let { PlainText(name, it) }
        }
    }

    fun session(activity: Activity, bridge: ProjectBridge, meta: JSONObject): Prepared {
        val name = artifactName(meta.getString("name"))
        val rawType = meta.optString("mediaType")
        val mime = artifactMediaType(name, rawType)
        val path = attachmentPath(meta.getString("relativePath"))
        return attachmentTree(bridge).use { tree ->
            if (image(meta, mime)) {
                val bitmap = runCatching { tree.readOnlyDescriptor(path).use { decode(it, 2560) } }.getOrNull()
                if (bitmap != null) return@use Picture(name, bitmap)
            }
            text(name, mime, rawType, meta.optLong("bytes", -1)) { tree.readOnlyDescriptor(path) }
                ?.let { return@use it }
            val output = ArtifactOpening.create(activity, name, mime)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(tree.readOnlyDescriptor(path)).use { input ->
                    output.stream.use { input.copyTo(it) }
                }
            } catch (e: Exception) { output.abort(); throw e }
            object : Prepared {
                override fun show(activity: Activity): AlertDialog? { output.commit(); return null }
                override fun discard() = output.abort()
            }
        }
    }

    fun workspace(activity: Activity, uri: Uri, row: JSONObject): Prepared {
        val name = artifactName(row.getString("name"))
        val rawType = activity.contentResolver.getType(uri) ?: row.optString("mimeType")
        val type = artifactMediaType(name, rawType)
        if (type.startsWith("image/")) {
            val bitmap = runCatching { activity.contentResolver.openFileDescriptor(uri, "r")?.use { decode(it, 2560) } }.getOrNull()
            if (bitmap != null) return Picture(name, bitmap)
        }
        text(name, type, rawType, row.optLong("bytes", -1)) { activity.contentResolver.openFileDescriptor(uri, "r") }
            ?.let { return it }
        return object : Prepared {
            override fun show(activity: Activity): AlertDialog? {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri(name, uri)
                activity.startActivity(intent)
                return null
            }
        }
    }
}

private class ZoomPicture(activity: Activity, bitmap: Bitmap) : ImageView(activity) {
    private val imageWidth = bitmap.width
    private val imageHeight = bitmap.height
    private var scaleFactor = 1f
    private var shiftX = 0f
    private var shiftY = 0f
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom(scaleFactor * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom(if (scaleFactor > 1.05f) 1f else 2.5f, e.x, e.y)
            return true
        }
        override fun onScroll(first: MotionEvent?, last: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleFactor > 1f && !pinch.isInProgress) { shiftX -= dx; shiftY -= dy; updateMatrix() }
            return true
        }
    })
    init { setImageBitmap(bitmap); scaleType = ScaleType.MATRIX }

    private fun zoom(next: Float, x: Float, y: Float) {
        val bounded = next.coerceIn(1f, 5f)
        val factor = bounded / scaleFactor
        shiftX = (shiftX - x + width / 2f) * factor + x - width / 2f
        shiftY = (shiftY - y + height / 2f) * factor + y - height / 2f
        scaleFactor = bounded
        updateMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }
    private fun updateMatrix() {
        if (width == 0 || height == 0) return
        val factor = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight) * scaleFactor
        val maxX = maxOf(0f, (imageWidth * factor - width) / 2f)
        val maxY = maxOf(0f, (imageHeight * factor - height) / 2f)
        shiftX = shiftX.coerceIn(-maxX, maxX)
        shiftY = shiftY.coerceIn(-maxY, maxY)
        val left = (width - imageWidth * factor) / 2f + shiftX
        val top = (height - imageHeight * factor) / 2f + shiftY
        val source = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        imageMatrix = Matrix().apply { setRectToRect(source, RectF(left, top, left + imageWidth * factor,
            top + imageHeight * factor), Matrix.ScaleToFit.FILL) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }
}

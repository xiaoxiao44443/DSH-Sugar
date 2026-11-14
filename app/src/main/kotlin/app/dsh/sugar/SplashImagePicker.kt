package app.dsh.sugar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Import the original bytes once; the selected document can subsequently move or lose its grant. */
internal class SplashImagePicker(private val activity: Activity, private val changed: (String?) -> Unit) {
    private var picking = false

    @Suppress("DEPRECATION")
    fun open() {
        if (picking) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                arrayOf("image/png", "image/apng", "image/vnd.mozilla.apng", "image/gif", "application/octet-stream"))
        try {
            activity.startActivityForResult(intent, REQUEST)
            picking = true
        } catch (_: ActivityNotFoundException) {
            changed(activity.sugarString(R.string.splash_image_picker_missing))
        }
    }

    fun result(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST) return false
        picking = false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK) changed(null)
        else if (uri?.scheme != ContentResolver.SCHEME_CONTENT) changed(activity.sugarString(R.string.splash_image_unreadable))
        else runImageChange { context -> importImage(context, uri) }
        return true
    }

    fun reset() = runImageChange { context ->
        selectImage(context, null, null)
        prune(context, null)
    }

    private fun runImageChange(action: (Context) -> Unit) {
        val context = activity.applicationContext
        imageIo.execute {
            val error = try {
                action(context)
                null
            } catch (e: InvalidImage) { e.message }
              catch (_: Exception) { activity.sugarString(R.string.splash_image_import_error) }
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing) changed(error)
            }
        }
    }

    private fun importImage(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val directory = SplashSettings.imageDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) throw IOException()
        val prefs = SplashSettings.preferences(context)
        prune(context, prefs.getString("imageFile", null))
        val image = File.createTempFile("splash-", ".img", directory)
        var published = false
        try {
            resolver.openInputStream(uri)?.use { input ->
                image.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 32L * 1024 * 1024) throw InvalidImage(activity.sugarString(R.string.splash_image_too_large))
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException()
            val mime = SplashSettings.imageMime(image) ?: throw InvalidImage(activity.sugarString(R.string.splash_image_types))
            if (!SplashImageFormat.complete(image, mime)) throw InvalidImage(activity.sugarString(R.string.splash_image_corrupt))
            verifyImage(image)
            val fallback = if (mime == "image/gif") activity.sugarString(R.string.splash_custom_gif) else activity.sugarString(R.string.splash_custom_png)
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.filterNot(Char::isISOControl)?.take(160)?.trim().orEmpty().ifEmpty { fallback }
            selectImage(context, image.name, name)
            published = true
            prune(context, image.name)
        } finally {
            if (!published) image.delete()
        }
    }

    private fun verifyImage(image: File) {
        // Decode a tiny first frame. The full animation stays in the original file for the WebView.
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(image)) { decoder, info, _ ->
                val size = info.size
                if (size.width.toLong() * size.height > 32_000_000L || maxOf(size.width, size.height) > 16384)
                    throw InvalidImage(activity.sugarString(R.string.splash_resolution))
                decoder.setTargetSize(1, 1)
            }.recycle()
        } catch (_: ImageDecoder.DecodeException) { throw InvalidImage(activity.sugarString(R.string.splash_image_corrupt)) }
    }

    private fun selectImage(context: Context, file: String?, name: String?) {
        val prefs = SplashSettings.preferences(context)
        val previousFile = prefs.getString("imageFile", null)
        val previousName = prefs.getString("imageName", null)
        if (!prefs.edit().putString("imageFile", file).putString("imageName", name).commit()) {
            // commit() already changed the in-memory preferences even if its disk write failed.
            prefs.edit().putString("imageFile", previousFile).putString("imageName", previousName).apply()
            throw IOException()
        }
    }

    private fun prune(context: Context, keep: String?) {
        SplashSettings.imageDirectory(context).listFiles()?.forEach {
            if (it.name != keep && it.name.startsWith("splash-") && it.name.endsWith(".img")) it.delete()
        }
    }

    private class InvalidImage(message: String) : IOException(message)

    companion object {
        private const val REQUEST = 0x5370
        // Serialize imports and resets across Activity recreation. Only commit after a successful decode.
        private val imageIo = Executors.newSingleThreadExecutor { Thread(it, "sugar-splash-images") }
    }
}

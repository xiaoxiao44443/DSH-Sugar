package app.dsh.sugar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Only copies prepared by a completed Android Open action receive a read grant. */
internal object ArtifactOpening {
    private const val MAX_AGE = 24L * 60 * 60 * 1000
    private val tokenPattern = Regex("[0-9a-f]{32}")
    private fun directory(context: Context) = File(context.cacheDir, "artifact-open")
    private fun expired(file: File) = System.currentTimeMillis() - file.lastModified() >= MAX_AGE

    fun clean(context: Context) {
        directory(context).listFiles()?.forEach { file ->
            if (tokenPattern.matches(file.name) && expired(file)) file.delete()
        }
    }

    fun create(activity: Activity, name: String, mime: String): ArtifactOutput {
        val context = activity.applicationContext
        val dir = directory(context)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException(context.sugarString(R.string.sugar_file_cache))
        clean(context)
        val title = artifactName(name)
        val token = UUID.randomUUID().toString().replace("-", "")
        val file = File(dir, token)
        val contentType = artifactMediaType(title, mime)
        val uri = Uri.Builder().scheme("content").authority(context.packageName + ".artifacts")
            .appendPath(token).appendPath(title).appendQueryParameter("type", contentType).build()
        return object : ArtifactOutput {
            override val stream: OutputStream = file.outputStream()

            override fun commit() {
                stream.close()
                val launch = FutureTask {
                    if (activity.isFinishing || activity.isDestroyed) throw IOException(context.sugarString(R.string.sugar_file_activity_closed))
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newRawUri(title, uri)
                    try { activity.startActivity(intent) }
                    catch (_: ActivityNotFoundException) { throw IOException("手机上没有可打开此类文件的应用。") }
                }
                activity.runOnUiThread(launch)
                try { launch.get() }
                catch (e: ExecutionException) { throw IOException(e.cause?.message ?: context.sugarString(R.string.sugar_file_open_failed), e.cause) }
            }

            override fun abort() {
                try { stream.close() } finally { file.delete() }
            }
        }
    }

    fun resolve(context: Context, uri: Uri): File {
        val parts = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != context.packageName + ".artifacts" ||
            parts.size != 2 || !tokenPattern.matches(parts[0])) throw FileNotFoundException()
        val file = File(directory(context), parts[0])
        if (!file.isFile || expired(file)) throw FileNotFoundException()
        return file
    }

    fun contentType(uri: Uri) = artifactMediaType(uri.lastPathSegment.orEmpty(), uri.getQueryParameter("type"))
}

/** Serving openable metadata and a seekable, read-only descriptor also supports PDF and video viewers. */
class ArtifactViewProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { app ->
            Thread({ ArtifactOpening.clean(app) }, "artifact-open-cleanup").start()
        }
        return true
    }

    override fun getType(uri: Uri): String {
        ArtifactOpening.resolve(requireNotNull(context), uri)
        return ArtifactOpening.contentType(uri)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only artifact")
        val file = ArtifactOpening.resolve(requireNotNull(context), uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor {
        val file = ArtifactOpening.resolve(requireNotNull(context), uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) uri.lastPathSegment else file.length() })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

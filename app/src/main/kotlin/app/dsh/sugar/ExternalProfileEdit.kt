package app.dsh.sugar

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.widget.Toast
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

internal class ExternalProfileEdit(private val activity: Activity) {
    data class Draft(val originalRevision: String, val bytes: ByteArray) {
        internal var editToken: String = ""
    }

    private val app = activity.applicationContext

    @Suppress("DEPRECATION")
    fun launch(bytes: ByteArray, revision: String): Boolean {
        var session: EditSession? = null
        return try {
            val ticket = ProfileCopies.create(app, bytes, revision)
            session = ticket
            val uri = ticket.uri(app)
            val edit = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "text/plain").apply {
                clipData = ClipData.newRawUri(PROFILE_NAME, uri)
                addFlags(URI_GRANTS)
            }
            if (activity.packageManager.queryIntentActivities(edit, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                throw EditProblem(R.string.sugar_file_editor_missing)
            }
            activity.startActivityForResult(Intent.createChooser(edit, app.sugarString(R.string.sugar_file_editor_choose)), ticket.code)
            true
        } catch (e: Exception) {
            session?.let { ProfileCopies.abandon(app, it) }
            notify(e, app.sugarString(R.string.sugar_file_editor_launch))
            false
        }
    }

    fun takeResult(requestCode: Int, callback: (Draft?) -> Unit): Boolean {
        if (requestCode !in ProfileCopies.CODES) return false
        try {
            if (!ProfileCopies.returned(app, requestCode)) return true
        } catch (e: Exception) {
            notify(e, app.sugarString(R.string.sugar_file_editor_receive))
            callback(null)
            return true
        }
        readInBackground(callback) { ProfileCopies.finish(app, requestCode) }
        return true
    }

    fun restoreReview(callback: (Draft?) -> Unit) =
        readInBackground(callback) { ProfileCopies.restore(app) }

    fun updateReview(draft: Draft, bytes: ByteArray): Boolean =
        try { ProfileCopies.update(app, draft, bytes) }
        catch (e: Exception) { notify(e, app.sugarString(R.string.sugar_file_editor_stage)); false }

    fun discardReview(draft: Draft) = ProfileCopies.discard(app, draft)

    private fun readInBackground(callback: (Draft?) -> Unit, read: () -> Draft?) {
        thread(name = "sugar-profile-result") {
            val result = runCatching(read)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    result.exceptionOrNull()?.let { notify(it, app.sugarString(R.string.sugar_file_editor_read)) }
                    callback(result.getOrNull())
                }
            }
        }
    }

    private fun notify(error: Throwable, fallback: String) {
        Toast.makeText(app, (error as? EditProblem)?.let { app.sugarString(it.messageId) } ?: fallback, Toast.LENGTH_LONG).show()
    }
}

private const val PROFILE_NAME = "cordis.patch.yml"
private const val URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
private const val MAX_BYTES = 1_048_576
private class EditProblem(val messageId: Int) : IOException()

private data class EditSession(val token: String, val code: Int, val revision: String, val digest: String, val created: Long) {
    fun uri(app: Context): Uri = Uri.Builder().scheme("content").authority(app.packageName + ".profile-edit")
        .appendPath(token).appendPath(PROFILE_NAME).build()
    fun encode(): String = JSONObject().put("token", token).put("code", code).put("revision", revision)
        .put("digest", digest).put("created", created).toString()
    fun draft(bytes: ByteArray) = ExternalProfileEdit.Draft(revision, bytes).also { it.editToken = token }
}

/** The provider runs in the application process. Serialize file access with session changes. */
private object ProfileCopies {
    val CODES = 0x3000..0x3fff
    private const val DAY = 24L * 60 * 60 * 1000
    private const val ACTIVE = "active"
    private const val REVIEW = "review"
    private const val RETURNED = "returned"
    private val handler = Handler(Looper.getMainLooper())
    private var cleanupTask: Runnable? = null
    private val tokenPattern = Regex("[a-f0-9]{32}")
    private fun directory(app: Context) = File(app.noBackupFilesDir, "profile-editor")
    private fun prefs(app: Context) = app.getSharedPreferences("profile-editor", Context.MODE_PRIVATE)
    private fun working(app: Context, s: EditSession) = File(directory(app), "edit-" + s.token)
    private fun review(app: Context, s: EditSession) = AtomicFile(File(directory(app), "review-" + s.token))
    private fun expired(s: EditSession) = System.currentTimeMillis() - s.created !in 0 until DAY
    private fun load(app: Context, key: String): EditSession? {
        val data = prefs(app).getString(key, null) ?: return null
        val json = JSONObject(data)
        val token = json.getString("token")
        check(tokenPattern.matches(token))
        return EditSession(token, json.getInt("code"), json.getString("revision"), json.getString("digest"), json.getLong("created"))
    }

    // Defer startup I/O. On process restart, purge unreferenced and expired files as well.
    @Synchronized fun scheduleCleanup(app: Context, delay: Long = 30_000L) {
        cleanupTask?.let(handler::removeCallbacks)
        val task = Runnable {
            thread(name = "sugar-profile-cleanup") {
                try {
                    synchronized(this) {
                        if (directory(app).isDirectory) {
                            val live = sweep(app)
                            live.minOfOrNull { it.created + DAY }?.let {
                                scheduleCleanup(app, (it - System.currentTimeMillis()).coerceAtLeast(1_000L))
                            }
                        }
                    }
                } catch (_: Exception) { android.util.Log.w("DSHSugar", "Profile editor cleanup deferred") }
            }
        }
        cleanupTask = task
        handler.postDelayed(task, delay)
    }

    private fun sweep(app: Context): List<EditSession> {
        val active = load(app, ACTIVE)
        val pending = load(app, REVIEW)
        if (active != null && expired(active)) abandon(app, active)
        if (pending != null && expired(pending)) clearReview(app, pending)
        val live = listOfNotNull(active, pending).filterNot(::expired)
        val activeName = active?.takeUnless(::expired)?.let { "edit-" + it.token }
        val reviewName = pending?.takeUnless(::expired)?.let { "review-" + it.token }
        directory(app).listFiles()?.forEach { file ->
            val base = file.name.substringBefore('.')
            if (base != activeName && base != reviewName && tokenPattern.matches(base.substringAfter('-'))) file.delete()
        }
        return live
    }

    @Synchronized fun create(app: Context, bytes: ByteArray, revision: String): EditSession {
        if (bytes.size > MAX_BYTES) throw EditProblem(R.string.sugar_file_config_limit)
        sweep(app)
        if (load(app, ACTIVE) != null) throw EditProblem(R.string.sugar_file_editor_busy)
        if (load(app, REVIEW) != null) throw EditProblem(R.string.sugar_file_editor_pending)
        val prefs = prefs(app)
        val last = prefs.getInt("lastCode", CODES.last)
        val code = if (last in CODES && last < CODES.last) last + 1 else CODES.first
        val s = EditSession(UUID.randomUUID().toString().replace("-", ""), code, revision, digest(bytes), System.currentTimeMillis())
        val root = directory(app)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create profile scratch")
        try {
            working(app, s).writeBytes(bytes)
            check(prefs.edit().putInt("lastCode", code).putString(ACTIVE, s.encode()).remove(RETURNED).commit())
        } catch (e: Exception) {
            working(app, s).delete()
            throw e
        }
        scheduleCleanup(app, DAY)
        return s
    }

    @Synchronized fun abandon(app: Context, s: EditSession) {
        if (load(app, ACTIVE)?.token != s.token) return
        try { app.revokeUriPermission(s.uri(app), URI_GRANTS) } finally { forgetActive(app, s) }
    }

    private fun forgetActive(app: Context, s: EditSession) {
        prefs(app).edit().remove(ACTIVE).remove(RETURNED).commit()
        working(app, s).delete()
    }

    @Synchronized fun returned(app: Context, code: Int): Boolean {
        val s = load(app, ACTIVE)?.takeIf { it.code == code } ?: return false
        check(prefs(app).edit().putString(RETURNED, s.token).commit())
        app.revokeUriPermission(s.uri(app), URI_GRANTS)
        return true
    }

    @Synchronized fun finish(app: Context, code: Int): ExternalProfileEdit.Draft? {
        val s = load(app, ACTIVE)?.takeIf { it.code == code } ?: return null
        try {
            if (expired(s)) throw EditProblem(R.string.sugar_file_editor_expired)
            val bytes = working(app, s).inputStream().use { boundedRead(it) }
            if (digest(bytes) == s.digest) return null
            try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)) }
            catch (_: java.nio.charset.CharacterCodingException) { throw EditProblem(R.string.sugar_file_editor_utf8) }
            writeReview(app, s, bytes)
            val pending = s.copy(created = System.currentTimeMillis())
            check(prefs(app).edit().putString(REVIEW, pending.encode()).commit())
            scheduleCleanup(app, DAY)
            return s.draft(bytes)
        } finally { forgetActive(app, s) }
    }

    @Synchronized fun restore(app: Context): ExternalProfileEdit.Draft? {
        // A previous Activity may have received the result just before process death.
        val sReturning = load(app, ACTIVE)
        if (sReturning != null && prefs(app).getString(RETURNED, null) == sReturning.token) {
            return finish(app, sReturning.code)
        }
        val s = load(app, REVIEW) ?: return null
        if (expired(s)) { clearReview(app, s); return null }
        return s.draft(review(app, s).openRead().use { boundedRead(it) })
    }

    @Synchronized fun update(app: Context, draft: ExternalProfileEdit.Draft, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_BYTES) throw EditProblem(R.string.sugar_file_draft_limit)
        val s = load(app, REVIEW)?.takeIf { it.token == draft.editToken && !expired(it) } ?: return false
        writeReview(app, s, bytes)
        return true
    }

    @Synchronized fun discard(app: Context, draft: ExternalProfileEdit.Draft) {
        load(app, REVIEW)?.takeIf { it.token == draft.editToken }?.let { clearReview(app, it) }
    }

    private fun clearReview(app: Context, s: EditSession) {
        prefs(app).edit().remove(REVIEW).commit()
        review(app, s).delete()
    }

    private fun writeReview(app: Context, s: EditSession, bytes: ByteArray) {
        val file = review(app, s)
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) { file.failWrite(out); throw e }
    }

    private fun boundedRead(input: java.io.FileInputStream): ByteArray {
        val size = input.channel.size()
        if (size !in 0L..MAX_BYTES.toLong()) throw EditProblem(R.string.sugar_file_edited_limit)
        val bytes = ByteArray(size.toInt())
        DataInputStream(input).readFully(bytes)
        if (input.read() != -1) throw EditProblem(R.string.sugar_file_editor_writing)
        return bytes
    }

    private fun digest(bytes: ByteArray): String =
        android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), android.util.Base64.NO_WRAP)

    @Synchronized fun <T> access(app: Context, uri: Uri, block: (File) -> T): T {
        val s = load(app, ACTIVE) ?: throw FileNotFoundException()
        if (s.uri(app) != uri || expired(s) || prefs(app).getString(RETURNED, null) == s.token) throw FileNotFoundException()
        return block(working(app, s))
    }
}

/** Only the one active edit URI is resolvable, including for metadata queries. */
class ExternalProfileProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { ProfileCopies.scheduleCleanup(it.applicationContext) }
        return true
    }

    override fun getType(uri: Uri): String = ProfileCopies.access(requireNotNull(context), uri) { "text/plain" }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor = ProfileCopies.access(requireNotNull(context), uri) { file ->
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) PROFILE_NAME else file.length() })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ProfileCopies.access(requireNotNull(context), uri) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

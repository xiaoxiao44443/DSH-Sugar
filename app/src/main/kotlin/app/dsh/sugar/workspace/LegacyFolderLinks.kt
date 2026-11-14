package app.dsh.sugar.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.JsonReader
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Retire the old two-copy synchronizer, including interrupted deletions. Never open a phone document. */
internal object LegacyFolderLinks {
    @Synchronized fun retire(context: Context): Boolean {
        val app = context.applicationContext
        val directory = File(app.noBackupFilesDir, "workspace-folders")
        // An older APK sometimes persisted an opt-in to deleting the phone tree before a crash.
        app.deleteSharedPreferences("workspace-confirmed-folder-deletions")
        val buckets = directory.listFiles() ?: return !directory.exists()
        var done = true
        for (bucket in buckets) {
            if (!bucket.isDirectory || !bucket.name.matches(Regex("[0-9a-f]{64}"))) continue
            try { retireOne(app, bucket) }
            catch (_: Exception) {
                done = false
                Log.w("DSHSugar", "Old workspace folder permission cleanup will be retried")
            }
        }
        directory.delete() // Only succeeds after the last empty metadata bucket is gone.
        return done
    }

    private fun retireOne(app: Context, bucket: File) {
        val base = File(bucket, "link.json")
        val metadata = AtomicFile(base)
        val input = try { metadata.openRead() } catch (_: FileNotFoundException) { return }
        var tree: Uri? = null
        var key: String? = null
        JsonReader(input.bufferedReader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "tree" -> tree = Uri.parse(reader.nextString())
                "key" -> key = reader.nextString()
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        val project = requireNotNull(key)
        val uri = requireNotNull(tree)
        require(project.byteInputStream().use { transferDigest(it) } == bucket.name &&
            uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
        val resolver = app.contentResolver
        resolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
            val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            if (flags != 0) resolver.releasePersistableUriPermission(uri, flags)
        }
        if (bucket.listFiles().orEmpty().any { it.name.startsWith("transfer.json") }) {
            // A half-finished legacy transfer can have a backup on the phone. Keep its recovery
            // information offline; do not finish, undo or remove either copy during migration.
            if (!base.renameTo(File(bucket, "retired-link.json"))) throw IOException("Could not archive old link")
        } else {
            metadata.delete()
            if (base.exists()) throw IOException("Could not retire old link")
            bucket.delete()
        }
    }
}

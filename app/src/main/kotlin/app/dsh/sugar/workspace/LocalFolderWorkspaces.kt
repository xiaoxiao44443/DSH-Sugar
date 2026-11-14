package app.dsh.sugar.workspace

import app.dsh.sugar.R
import app.dsh.sugar.sugarString

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.StructStat
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/** A grant is paired with one registered workspace ID. File contents stay with the Android provider. */
internal object LocalFolderWorkspaces {
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val slugPattern = Regex("local-[0-9a-f]{64}")
    private val random = SecureRandom()
    data class Selection(val slug: String, val title: String, val project: String?)
    data class Binding(val tree: Uri, val device: Long, val inode: Long)
    data class FolderRoot(val tree: Uri, val stat: StructStat)

    private fun storage(context: Context) = context.getSharedPreferences("local-folder-workspaces-v1", Context.MODE_PRIVATE)
    private fun identity(context: Context, tree: Uri, signal: CancellationSignal? = null): StructStat {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return checkNotNull(context.contentResolver.openFileDescriptor(root, "r", signal)).use {
            android.system.Os.fstat(it.fileDescriptor).also { stat ->
                check(android.system.OsConstants.S_ISDIR(stat.st_mode) && stat.st_ino != 0L) { context.sugarString(R.string.ws_unverified_identity) }
            }
        }
    }
    private fun slugOf(root: String) = root.removePrefix("/workspace/").takeIf {
        root == "/workspace/$it" && slugPattern.matches(it)
    }
    fun isDirectRoot(root: String) = slugOf(root) != null

    @Synchronized fun select(context: Context, tree: Uri, flags: Int): Selection {
        require(tree.scheme == "content" && tree.authority == AUTHORITY && DocumentsContract.isTreeUri(tree)) {
            context.sugarString(R.string.ws_choose_volume)
        }
        require(flags and ACCESS == ACCESS) { context.sugarString(R.string.ws_folder_permission) }
        val id = DocumentsContract.getTreeDocumentId(tree)
        val uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, id)
        require(uri == tree) { context.sugarString(R.string.ws_invalid_permission) }
        val storage = storage(context)
        var slug: String? = null
        var existing: JSONObject? = null
        for ((key, value) in storage.all) {
            val record = JSONObject(value as String)
            val other = DocumentsContract.getTreeDocumentId(Uri.parse(record.getString("uri")))
            if (id == other) { slug = key; existing = record; continue }
            val a = id.substringBefore(':')
            val b = other.substringBefore(':')
            if (a == b) {
                val selected = "/" + id.substringAfter(':').trim('/')
                val prior = "/" + other.substringAfter(':').trim('/')
                require(!selected.startsWith(prior.trimEnd('/') + "/") && !prior.startsWith(selected.trimEnd('/') + "/")) {
                    context.sugarString(R.string.ws_overlap)
                }
            }
        }
        val resolver = context.contentResolver
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, id)
        val title = resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS), null, null, null)?.use {
            check(it.moveToFirst() && it.getString(1) == Document.MIME_TYPE_DIR &&
                it.getLong(2) and Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L) { context.sugarString(R.string.ws_cannot_create) }
            it.getString(0)
        } ?: error(context.sugarString(R.string.ws_cannot_open))
        val key = slug ?: "local-" + ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val hadGrant = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        resolver.takePersistableUriPermission(uri, ACCESS)
        try {
            val row = existing ?: JSONObject().put("uri", uri.toString())
            val stat = identity(context,uri)
            row.put("dev", stat.st_dev).put("ino", stat.st_ino)
            check(storage.edit().putString(key, row.toString()).commit()) { context.sugarString(R.string.ws_cannot_save_permission) }
            return Selection(key, title, row.optString("id").ifEmpty { null })
        } catch (e: Exception) {
            if (!hadGrant) runCatching { resolver.releasePersistableUriPermission(uri, ACCESS) }
            throw e
        }
    }

    @Synchronized fun bind(context: Context, slug: String, id: String) {
        require(slugPattern.matches(slug) && UUID.fromString(id).toString() == id)
        val store = storage(context)
        val row = JSONObject(checkNotNull(store.getString(slug, null)))
        check(!row.has("id") || row.getString("id") == id) { context.sugarString(R.string.ws_already_bound) }
        check(store.edit().putString(slug, row.put("id", id).toString()).commit())
    }

    /** Read binding metadata without opening a provider or traversing phone storage. */
    @Synchronized fun registeredFolders(context: Context): Map<String, Pair<String, String>> = buildMap {
        for ((slug, value) in storage(context).all) {
            if (!slugPattern.matches(slug)) continue
            val row = JSONObject(value as String)
            val id = row.optString("id")
            if (id.isBlank()) continue
            val location = DocumentsContract.getTreeDocumentId(Uri.parse(row.getString("uri")))
            put(id, "/workspace/$slug" to location)
        }
    }

    fun treeFor(context: Context, id: String, root: String): Uri? = verifiedRoot(context,id,root)?.tree

    fun bindingFor(context: Context, id: String, root: String): Binding? = verifiedRoot(context,id,root)?.let {
        Binding(it.tree, it.stat.st_dev, it.stat.st_ino)
    }

    fun writableTrees(context: Context): Set<Uri> = context.contentResolver.persistedUriPermissions.asSequence()
        .filter { it.isReadPermission && it.isWritePermission }.map { it.uri }.toSet()

    @Synchronized fun verifiedRoot(context: Context, id: String, root: String,
                                   signal: CancellationSignal? = null, grants: Set<Uri>? = null): FolderRoot? {
        val slug = slugOf(root) ?: return null
        val row = JSONObject(checkNotNull(storage(context).getString(slug, null)) { context.sugarString(R.string.ws_reselect_workspace) })
        check(row.optString("id") == id) { context.sugarString(R.string.ws_registration_pending) }
        val uri = Uri.parse(row.getString("uri"))
        check(uri in (grants ?: writableTrees(context))) {
            context.sugarString(R.string.ws_permission_expired)
        }
        val stat = identity(context,uri,signal)
        check(row.getLong("dev") == stat.st_dev && row.getLong("ino") == stat.st_ino) {
            context.sugarString(R.string.ws_folder_replaced)
        }
        return FolderRoot(uri,stat)
    }

    /** Drop only this project's capability. Never delete the external folder or its contents. */
    @Synchronized fun forget(context: Context, id: String, root: String) {
        val slug = slugOf(root) ?: return
        val store = storage(context)
        val row = store.getString(slug, null)?.let(::JSONObject) ?: return
        if (row.optString("id") != id) return
        val uri = Uri.parse(row.getString("uri"))
        if (context.contentResolver.persistedUriPermissions.any { it.uri == uri }) {
            context.contentResolver.releasePersistableUriPermission(uri, ACCESS)
        }
        check(store.edit().remove(slug).commit())
    }
}

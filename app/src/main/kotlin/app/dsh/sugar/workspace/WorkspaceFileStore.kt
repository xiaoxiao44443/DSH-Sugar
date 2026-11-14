package app.dsh.sugar.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.OsConstants
import app.dsh.sugar.artifactMediaType
import app.dsh.sugar.runtime.ProjectSandboxes
import com.sucrune.probe.NativeProbe
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException

internal class WorkspaceFileStore(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    fun list(workspaceId: String, relativePath: String): JSONObject {
        val parts = workspaceFilePath(relativePath)
        val project = registered(workspaceId)
        val phone = phoneTree(workspaceId, project)
        val rows = if (phone == null) {
            withPrivate(workspaceId) { tree ->
                tree.inDocumentDirectory(relativePath) { directory ->
                    val contents = JSONArray()
                    for ((name, stat) in tree.documentChildren(directory)) {
                        val kind = when {
                            OsConstants.S_ISDIR(stat.st_mode) -> "directory"
                            OsConstants.S_ISREG(stat.st_mode) -> "file"
                            OsConstants.S_ISLNK(stat.st_mode) -> "link"
                            else -> continue
                        }
                        val row = row(parts, name, kind, true)
                        if (kind == "file") {
                            row.put("bytes", stat.st_size)
                            row.put("mimeType", artifactMediaType(name, null))
                        }
                        val modified = stat.st_mtim.tv_sec * 1000L + stat.st_mtim.tv_nsec / 1_000_000L
                        row.put("modifiedAt", modified)
                        creationTime(directory.fd, name, stat.st_ino)?.let { row.put("createdAt", it) }
                        contents.put(row)
                    }
                    contents
                }
            }
        } else {
            val contents = JSONArray()
            for (entry in phoneChildren(phone, parts)) {
                val kind = if (entry.mime == Document.MIME_TYPE_DIR) "directory" else "file"
                val row = row(parts, entry.name, kind, entry.canDelete)
                if (kind == "file") {
                    entry.size?.let { row.put("bytes", it) }
                    row.put("mimeType", artifactMediaType(entry.name, entry.mime))
                }
                entry.modified?.let { row.put("modifiedAt", it) }
                contents.put(row)
            }
            contents
        }
        return JSONObject().put("project", workspaceId).put("path", relativePath)
            .put("title", project.getString("title")).put("projectName", project.getString("title"))
            .put("phoneFolder", phone != null).put("rows", rows)
    }

    fun openUri(workspaceId: String, relativePath: String): Uri {
        val parts = workspaceFilePath(relativePath)
        require(parts.isNotEmpty())
        val phone = phoneTree(workspaceId, registered(workspaceId))
        if (phone != null) {
            val entry = phoneEntry(phone, parts)
            require(entry.mime != Document.MIME_TYPE_DIR)
            return DocumentsContract.buildDocumentUriUsingTree(phone, entry.id)
        }
        return withPrivate(workspaceId) { tree ->
            require(OsConstants.S_ISREG(tree.documentStat(relativePath).st_mode))
            projectUri(workspaceId, relativePath)
        }
    }

    fun delete(workspaceId: String, relativePath: String, expectedKind: String) {
        val parts = workspaceFilePath(relativePath)
        require(parts.isNotEmpty())
        require(expectedKind in setOf("directory", "file", "link"))
        val phone = phoneTree(workspaceId, registered(workspaceId))
        if (phone == null) {
            val uri = projectUri(workspaceId, relativePath)
            if (expectedKind == "directory") {
                withPrivate(workspaceId) { tree -> require(OsConstants.S_ISDIR(tree.documentStat(relativePath).st_mode)) }
                check(DocumentsContract.deleteDocument(resolver, uri))
            } else {
                withPrivate(workspaceId) { tree ->
                    tree.inDocumentDirectory(parts.dropLast(1).joinToString("/")) { parent ->
                        val mode = tree.documentStat(relativePath).st_mode
                        require(if (expectedKind == "file") OsConstants.S_ISREG(mode) else OsConstants.S_ISLNK(mode))
                        // Even if the entry changes type here, unlink cannot delete a directory.
                        val error = NativeProbe.unlinkDirectoryEntry(parent.fd, parts.last(), false)
                        if (error != 0) throw ErrnoException("unlinkat", -error)
                    }
                }
                app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val parentId = (listOf("p:$workspaceId") + parts.dropLast(1)).joinToString("/")
                resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(app.packageName + ".workspaces", parentId), null)
            }
        } else {
            val entry = phoneEntry(phone, parts)
            require(entry.canDelete)
            val actualKind = if (entry.mime == Document.MIME_TYPE_DIR) "directory" else "file"
            require(actualKind == expectedKind)
            if (expectedKind == "directory") {
                // The user confirmed deletion of this folder and all its children.
                val uri = DocumentsContract.buildDocumentUriUsingTree(phone, entry.id)
                check(DocumentsContract.deleteDocument(resolver, uri))
            } else {
                // A regular-file selection must never turn into a recursive SAF deletion.
                LocalDocumentDirectory(app, phone).remove(relativePath, false)
            }
        }
    }

    private fun registered(id: String): JSONObject =
        ProjectSandboxes.storageRegistry(app).getJSONObject("items").optJSONObject(id)
            ?: throw FileNotFoundException()

    private fun phoneTree(id: String, project: JSONObject): Uri? {
        if (!project.getBoolean("external")) return null
        return checkNotNull(LocalFolderWorkspaces.verifiedRoot(app, id, project.getString("root"))).tree
    }

    private fun <T> withPrivate(id: String, action: (PrivateWorkspaceTree) -> T): T =
        ProjectSandboxes.withDocumentProject(app, id) { _, directory -> PrivateWorkspaceTree(app, directory).use(action) }

    private fun projectUri(id: String, relative: String) =
        DocumentsContract.buildDocumentUri(app.packageName + ".workspaces", "p:$id/$relative")

    private fun row(parent: List<String>, name: String, kind: String, deletable: Boolean) =
        JSONObject().put("name", name).put("path", (parent + name).joinToString("/"))
            .put("kind", kind).put("canDelete", deletable)

    private data class PhoneEntry(val id: String, val name: String, val mime: String, val size: Long?,
                                  val modified: Long?, val canDelete: Boolean)

    private fun phoneEntry(tree: Uri, parts: List<String>) =
        phoneChildren(tree, parts.dropLast(1)).singleOrNull { it.name == parts.last() } ?: throw FileNotFoundException()

    private fun phoneChildren(tree: Uri, parent: List<String>): List<PhoneEntry> {
        val directory = LocalDocumentDirectory(app, tree).directoryUri(parent)
        val parentId = DocumentsContract.getDocumentId(directory)
        val expectedParent = parent.fold(DocumentsContract.getTreeDocumentId(tree), ::phoneChildId)
        check(parentId == expectedParent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = resolver.query(children, columns, null, null, null) ?: throw FileNotFoundException()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    if (runCatching { checkedName(name) }.isFailure) continue
                    val id = it.getString(0) ?: continue
                    // Only ExternalStorageProvider's verified, path-based tree grants reach here.
                    if (id != phoneChildId(parentId, name)) continue
                    if (name.startsWith(".dsh-unlink-") && app.getSharedPreferences("local-regular-file-removals",
                            Context.MODE_PRIVATE).contains(name)) continue
                    val mime = it.getString(2) ?: "application/octet-stream"
                    add(PhoneEntry(id, name, mime, if (it.isNull(3)) null else it.getLong(3).takeIf { n -> n >= 0 },
                        if (it.isNull(4)) null else it.getLong(4).takeIf { n -> n > 0 },
                        it.getInt(5) and Document.FLAG_SUPPORTS_DELETE != 0))
                }
            }
        }
    }

    private fun creationTime(fd: Int, name: String, inode: Long): Long? {
        if (Build.VERSION.SDK_INT < 30 || !birthTimeAvailable) return null
        return try {
            NativeProbe.directoryEntryBirthMillis(fd, name, inode).takeIf { it >= 0 }
        } catch (_: UnsatisfiedLinkError) {
            // A previous Sucrune validation APK can still provide all other file operations.
            birthTimeAvailable = false
            null
        }
    }

    private companion object {
        @Volatile var birthTimeAvailable = true
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}

private fun phoneChildId(parent: String, name: String) =
    if (parent.substringAfter(':').isEmpty()) parent + name else "$parent/$name"

internal fun workspaceFilePath(relative: String): List<String> {
    if (relative.isEmpty()) return emptyList()
    require(relative.toByteArray().size <= 4095)
    return relative.split('/').onEach { name ->
        checkedName(name)
        require(name.toByteArray().size <= 255)
    }
}

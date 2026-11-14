package app.dsh.sugar.workspace

import app.dsh.sugar.sugarString

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.OsConstants
import android.system.StructStat
import app.dsh.sugar.R
import app.dsh.sugar.artifactMediaType
import app.dsh.sugar.runtime.ProjectSandboxes
import java.io.File
import java.io.FileNotFoundException

/** Android's Files UI can browse project workspaces without starting a DSH session. */
class WorkspaceDocumentsProvider : DocumentsProvider() {
    private val app get() = requireNotNull(context)
    private val authority get() = app.packageName + ".workspaces"
    private val rootsUri get() = DocumentsContract.buildRootsUri(authority)
    private val linux by lazy { InternalLinuxFiles(app) }
    private val internalTitle get() = app.sugarString(R.string.ws_internal_files)

    override fun onCreate(): Boolean {
        val cache = app.cacheDir
        Thread({ WorkspaceRemoval.sweep(cache) }, "workspace-trash-cleanup").start()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: rootColumns)
        cursor.row(mapOf(Root.COLUMN_ROOT_ID to ROOT_ID, Root.COLUMN_DOCUMENT_ID to ROOT_ID,
            Root.COLUMN_TITLE to "DSH Sugar", Root.COLUMN_ICON to R.mipmap.ic_launcher,
            Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD),
            Root.COLUMN_MIME_TYPES to "*/*"))
        cursor.row(mapOf(Root.COLUMN_ROOT_ID to INTERNAL, Root.COLUMN_DOCUMENT_ID to INTERNAL,
            Root.COLUMN_TITLE to internalTitle, Root.COLUMN_ICON to R.mipmap.ic_launcher,
            Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE),
            Root.COLUMN_MIME_TYPES to "*/*"))
        cursor.setNotificationUri(app.contentResolver, rootsUri)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = checked(app) {
        val cursor = MatrixCursor(projection ?: documentColumns)
        if (internalId(documentId)) cursor.internalDocument(documentId, linux.stat(internalParts(documentId)))
        else if (documentId == ROOT_ID) cursor.document(ROOT_ID, "DSH Sugar", null)
        else {
            val item = Item.parse(documentId)
            if (item.parts.isEmpty()) {
                val info = ProjectSandboxes.documentProjects(app)[item.project] ?: throw FileNotFoundException()
                cursor.document(documentId, info.title, null, if (info.deletable) Document.FLAG_SUPPORTS_DELETE else 0)
            } else withTree(app, item) { _, tree ->
                cursor.document(documentId, item.parts.last(), tree.documentStat(item.relative))
            }
        }
        cursor.setNotificationUri(app.contentResolver, rootsUri)
        cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = checked(app) {
        val cursor = Listing(projection ?: documentColumns)
        val children = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        try {
            if (internalId(parentDocumentId)) {
                val parts = internalParts(parentDocumentId)
                val entries = linux.list(parts) { directory -> cursor.watch(directory) { app.contentResolver.notifyChange(children, null) } }
                for (entry in entries) cursor.internalDocument(idFor(parts + entry.name), entry)
            } else if (parentDocumentId == ROOT_ID) {
                for ((id, info) in ProjectSandboxes.documentProjects(app)) {
                    cursor.document(Item(id).id, info.title, null, if (info.deletable) Document.FLAG_SUPPORTS_DELETE else 0)
                }
            } else {
                val parent = Item.parse(parentDocumentId)
                withTree(app, parent) { _, tree ->
                    tree.inDocumentDirectory(parent.relative) { dir ->
                        val path = File("/proc/self/fd/${dir.fd}")
                        cursor.watch(path) { app.contentResolver.notifyChange(children, null) }
                        for ((name, stat) in tree.documentChildren(dir)) {
                            if (visible(stat)) cursor.document(parent.child(name).id, name, stat)
                        }
                    }
                }
            }
            cursor.setNotificationUris(app.contentResolver, listOf(rootsUri, children))
            cursor
        } catch (e: Throwable) { cursor.close(); throw e }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = checked(app) {
        signal?.throwIfCanceled()
        if (internalId(documentId)) return@checked linux.open(internalParts(documentId), mode)
        if (mode != "r") throw FileNotFoundException(app.sugarString(R.string.ws_read_only))
        val item = Item.parse(documentId)
        require(item.parts.isNotEmpty())
        withTree(app, item) { _, tree -> tree.readOnlyDescriptor(item.relative) }
    }

    override fun deleteDocument(documentId: String) = checked(app) {
        if (internalId(documentId)) {
            val parts = internalParts(documentId)
            require(parts.isNotEmpty())
            try {
                linux.delete(parts) { removed, owner, relative ->
                    revokeDocumentPermission(idFor(removed))
                    if (owner != null) revokeProjectDocument(owner, relative)
                }
            } finally { notifyInternal(parts.dropLast(1)) }
            return@checked
        }
        val item = Item.parse(documentId)
        if (item.parts.isEmpty()) {
            val info = ProjectSandboxes.documentProjects(app)[item.project] ?: throw FileNotFoundException()
            require(info.deletable) { app.sugarString(R.string.ws_delete_root) }
            val root = info.path.removePrefix("/").split('/')
            try {
                ProjectSandboxes.deleteRetainedDocument(app, item.project, info.path) { removed ->
                    val relative = if (removed.isEmpty()) emptyList() else removed.split('/')
                    revokeProjectDocument(item.project, relative)
                    revokeDocumentPermission(idFor(root + relative))
                }
            } finally { notifyInternal(root.dropLast(1)) }
            return@checked
        }
        val parent = item.copy(parts = item.parts.dropLast(1))
        try {
            withTree(app, item) { _, tree ->
                tree.deleteEntry(item.relative) { removed ->
                    revokeProjectDocument(item.project, removed.split('/'))
                }
            }
        } finally {
            app.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parent.id), null)
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = checked(app) {
        val parent = internalParts(parentDocumentId)
        checkedName(displayName)
        linux.create(parent, displayName, mimeType == Document.MIME_TYPE_DIR)
        notifyInternal(parent)
        idFor(parent + displayName)
    }

    override fun renameDocument(documentId: String, displayName: String): String = checked(app) {
        val parts = internalParts(documentId)
        require(parts.isNotEmpty())
        checkedName(displayName)
        linux.rename(parts, displayName)
        val parent = parts.dropLast(1)
        notifyInternal(parent)
        idFor(parent + displayName)
    }

    private fun notifyInternal(parent: List<String>) {
        app.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, idFor(parent)), null)
        app.contentResolver.notifyChange(rootsUri, null)
    }

    private fun revokeProjectDocument(project: String, parts: List<String>) {
        val id = runCatching { Item(project, parts).id }.getOrNull()
        if (id != null) revokeDocumentPermission(id)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        if (internalId(parentDocumentId) && internalId(documentId)) {
            val parent = internalParts(parentDocumentId)
            val child = internalParts(documentId)
            // A tree grant for a user folder must not cover protected control files inside it.
            child.size > parent.size && child.take(parent.size) == parent &&
                linux.stat(parent).creatable && linux.stat(child).let { it.creatable || it.writable }
        } else {
            val child = Item.parse(documentId)
            (parentDocumentId == ROOT_ID || child.parents().contains(parentDocumentId)) &&
                withTree(app, child) { _, tree -> visible(tree.documentStat(child.relative)) }
        }
    } catch (_: Exception) { false }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path = checked(app) {
        val internal = internalId(childDocumentId)
        val path = if (internal) {
            val parts = internalParts(childDocumentId)
            linux.stat(parts)
            (0..parts.size).map { idFor(parts.take(it)) }
        } else if (childDocumentId == ROOT_ID) listOf(ROOT_ID) else {
            val child = Item.parse(childDocumentId)
            withTree(app, child) { _, tree -> require(visible(tree.documentStat(child.relative))) }
            child.parents() + child.id
        }
        val first = if (parentDocumentId == null) 0 else path.indexOf(parentDocumentId)
        require(first >= 0)
        val root = if (internal) INTERNAL else ROOT_ID
        DocumentsContract.Path(root.takeIf { parentDocumentId == null }, path.drop(first))
    }

    private fun MatrixCursor.internalDocument(id: String, entry: InternalLinuxFiles.Entry) {
        var flags = 0
        if (entry.writable) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (entry.creatable) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (entry.deletable) flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (entry.renamable) flags = flags or Document.FLAG_SUPPORTS_RENAME
        if (Build.VERSION.SDK_INT >= 30 && !entry.creatable && (entry.stat == null || OsConstants.S_ISDIR(entry.stat.st_mode)))
            flags = flags or Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE
        document(id, if (id == INTERNAL) internalTitle else entry.name, entry.stat, flags)
    }

    private fun MatrixCursor.document(id: String, title: String, stat: StructStat?, internalFlags: Int? = null) {
        if (stat != null) require(visible(stat))
        val directory = stat == null || OsConstants.S_ISDIR(stat.st_mode)
        val flags = internalFlags ?: when {
            id != ROOT_ID && Item.parse(id).parts.isNotEmpty() -> Document.FLAG_SUPPORTS_DELETE
            id == ROOT_ID && Build.VERSION.SDK_INT >= 30 -> Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE
            else -> 0
        }
        val mime = when {
            directory -> Document.MIME_TYPE_DIR
            stat != null && OsConstants.S_ISLNK(stat.st_mode) -> "application/octet-stream"
            else -> artifactMediaType(title, null)
        }
        row(mapOf(Document.COLUMN_DOCUMENT_ID to id, Document.COLUMN_DISPLAY_NAME to title,
            Document.COLUMN_MIME_TYPE to mime, Document.COLUMN_FLAGS to flags,
            Document.COLUMN_SIZE to stat?.st_size?.takeUnless { directory },
            Document.COLUMN_LAST_MODIFIED to stat?.st_mtime?.times(1000)))
    }

    private fun MatrixCursor.row(values: Map<String, Any?>) = addRow(columnNames.map { values[it] })

    private class Listing(columns: Array<out String>) : MatrixCursor(columns) {
        private val observers = mutableListOf<FileObserver>()
        fun watch(directory: File, changed: () -> Unit) {
            val events = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.ATTRIB or
                FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
            observers += object : FileObserver(directory, events) {
                override fun onEvent(event: Int, path: String?) = changed()
            }.also { it.startWatching() }
        }
        override fun close() { observers.forEach { it.stopWatching() }; observers.clear(); super.close() }
    }

    private data class Item(val project: String, val parts: List<String> = emptyList()) {
        val relative get() = parts.joinToString("/")
        val id get() = (listOf("p:$project") + parts).joinToString("/")
        init {
            require(uuid.matches(project) && parts.size < 2048)
            parts.forEach(::checkedName)
            require(relative.toByteArray().size <= 4095)
        }
        fun child(name: String) = copy(parts = parts + name)
        fun parents() = listOf(ROOT_ID) + (0 until parts.size).map { copy(parts = parts.take(it)).id }
        companion object {
            fun parse(id: String): Item {
                require(id.length <= 4200 && id.startsWith("p:"))
                val parts = id.drop(2).split('/')
                return Item(parts.first(), parts.drop(1))
            }
        }
    }

    companion object {
        private const val ROOT_ID = "projects"
        private const val INTERNAL = "internal"
        private fun internalId(id: String) = id == INTERNAL || id.startsWith("$INTERNAL/")
        private fun idFor(parts: List<String>) = (listOf(INTERNAL) + parts).joinToString("/")
        private fun internalParts(id: String): List<String> {
            require(internalId(id) && id.toByteArray().size <= 4110)
            if (id == INTERNAL) return emptyList()
            return id.substring(INTERNAL.length + 1).split('/').onEach(::checkedName)
        }
        fun internalRoot(context: Context): Uri =
            DocumentsContract.buildDocumentUri(context.packageName + ".workspaces", INTERNAL)
        private val uuid = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val rootColumns = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES)
        private val documentColumns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

        private fun visible(stat: StructStat) = OsConstants.S_ISDIR(stat.st_mode) ||
            OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)

        private fun <T> withTree(context: Context, item: Item, action: (String, PrivateWorkspaceTree) -> T): T =
            ProjectSandboxes.withDocumentProject(context, item.project) { title, folder ->
                PrivateWorkspaceTree(context, folder).use { action(title, it) }
            }

        private inline fun <T> checked(context: Context, block: () -> T): T = try { block() }
        catch (e: android.os.OperationCanceledException) { throw e }
        catch (_: Exception) { throw FileNotFoundException(context.sugarString(R.string.ws_file_unavailable)) }

        fun uriForDirectory(context: Context, workspaceId: String, components: List<String>): Uri = checked(context) {
            val item = Item(workspaceId, components)
            withTree(context, item) { _, tree -> require(OsConstants.S_ISDIR(tree.documentStat(item.relative).st_mode)) }
            DocumentsContract.buildDocumentUri(context.packageName + ".workspaces", item.id)
        }
    }
}

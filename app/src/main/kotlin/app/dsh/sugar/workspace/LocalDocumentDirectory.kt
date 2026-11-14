package app.dsh.sugar.workspace

import app.dsh.sugar.R
import app.dsh.sugar.sugarString

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.dsh.sugar.artifactMediaType
import com.sucrune.probe.GuestDirectory
import com.sucrune.probe.NativeProbe
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Sucrune's external mount, backed directly by the user's local DocumentsUI grant. */
internal class LocalDocumentDirectory(context: Context, private val tree: Uri) : GuestDirectory {
    private val resolver = context.applicationContext.contentResolver
    private val removals = context.applicationContext.getSharedPreferences("local-regular-file-removals", Context.MODE_PRIVATE)
    private val rootId: String
    private val rootIdentity: Pair<Long, Long>

    init {
        require(tree.scheme == "content" && tree.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.isTreeUri(tree)) { context.sugarString(R.string.ws_choose_local) }
        rootId = DocumentsContract.getTreeDocumentId(tree)
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            fail("mount", OsConstants.EACCES)
        }
        rootIdentity = currentRootIdentity()
    }

    private data class Entry(val id: String, val name: String, val directory: Boolean,
                             val flags: Int, val size: Long, val modified: Long)
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    init {
        synchronized(removalLock) {
            for ((ticket, record) in removals.all) {
                if (record !is String) continue
                val state = runCatching { JSONObject(record) }.getOrNull() ?: continue
                if (state.optString("tree") == tree.toString() && state.optLong("rootDev") == rootIdentity.first &&
                    state.optLong("rootIno") == rootIdentity.second) {
                    // A stopped manager can leave a file between detachment and the provider's delete.
                    // Failure to access this particular parent must not disable unrelated project files.
                    runCatching { completeRemoval(ticket, state) }
                }
            }
        }
    }
    private fun document(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun children(id: String): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)

    private fun currentRootIdentity(): Pair<Long, Long> {
        val handle = resolver.openFileDescriptor(document(rootId), "r") ?: fail("mount", OsConstants.ESTALE)
        return handle.use {
            val stat = Os.fstat(it.fileDescriptor)
            if (!OsConstants.S_ISDIR(stat.st_mode)) fail("mount", OsConstants.ESTALE)
            stat.st_dev to stat.st_ino
        }
    }

    private fun checkRoot() {
        if (currentRootIdentity() != rootIdentity) fail("mount", OsConstants.ESTALE)
    }

    private fun listAt(uri: Uri): List<Entry> {
        val cursor = resolver.query(uri, columns, null, null, null) ?: fail("query", OsConstants.EIO)
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    if (runCatching { checkedName(name) }.isFailure) continue
                    add(Entry(it.getString(0), name, it.getString(2) == Document.MIME_TYPE_DIR,
                        it.getInt(3), it.getLong(4), it.getLong(5)))
                }
            }
        }
    }

    private fun lookup(uri: Uri): Entry = listAt(uri).singleOrNull() ?: fail("lookup", OsConstants.ENOENT)
    private fun child(parent: Entry, name: String): Entry? {
        if (!parent.directory) fail("lookup", OsConstants.ENOTDIR)
        val matches = listAt(children(parent.id)).filter { it.name == name }
        if (matches.size > 1) fail("lookup", OsConstants.EIO)
        return matches.firstOrNull()
    }
    private fun parts(path: String): List<String> {
        if (path.isEmpty()) return emptyList()
        val parts = path.split('/')
        if (path.toByteArray().size > 4095 || parts.any { it.toByteArray().size > 255 }) fail("lookup", OsConstants.ENAMETOOLONG)
        if (parts.any { runCatching { checkedName(it) }.isFailure }) fail("lookup", OsConstants.EINVAL)
        if (parts.any(::isDetached)) fail("lookup", OsConstants.ENOENT)
        return parts
    }

    private fun isDetached(name: String) = name.startsWith(detachedPrefix) && removals.contains(name)
    private fun resolve(parts: List<String>): Entry {
        var entry = lookup(document(rootId))
        for (name in parts) entry = child(entry, name) ?: fail("lookup", OsConstants.ENOENT)
        return entry
    }
    private fun parent(path: String): Pair<Entry,String> {
        val parts = parts(path)
        if (parts.isEmpty()) fail("lookup", OsConstants.EBUSY)
        return resolve(parts.dropLast(1)) to parts.last()
    }

    @Synchronized fun directoryUri(components: List<String>): Uri = operation("directoryUri") {
        components.forEach(::checkedName)
        val dir = resolve(parts(components.joinToString("/")))
        if (!dir.directory) fail("directoryUri", OsConstants.ENOTDIR)
        document(dir.id)
    }

    @Synchronized override fun stat(path: String): LongArray = operation("stat") {
        val file = resolve(parts(path))
        val read = OsConstants.S_IRUSR or OsConstants.S_IRGRP or OsConstants.S_IROTH
        val write = OsConstants.S_IWUSR or OsConstants.S_IWGRP or OsConstants.S_IWOTH
        val search = OsConstants.S_IXUSR or OsConstants.S_IXGRP or OsConstants.S_IXOTH
        val mode = if (file.directory) {
            OsConstants.S_IFDIR or read or search or (if (file.flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0) write else 0)
        } else {
            OsConstants.S_IFREG or read or (if (file.flags and Document.FLAG_SUPPORTS_WRITE != 0) write else 0)
        }
        val metadata = longArrayOf(mode.toLong(), file.size, file.modified)
        if (!file.directory) return@operation metadata
        try {
            directoryFd(file).use {
                val identity = Os.fstat(it.fileDescriptor)
                metadata + longArrayOf(identity.st_dev, identity.st_ino)
            }
        } catch (_: FileNotFoundException) { metadata }
        catch (_: ErrnoException) { metadata }
    }

    private fun directoryFd(dir: Entry): ParcelFileDescriptor {
        if (!dir.directory) fail("opendir", OsConstants.ENOTDIR)
        val fd = resolver.openFileDescriptor(document(dir.id), "r") ?: fail("opendir", OsConstants.EOPNOTSUPP)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd.fileDescriptor).st_mode)) fail("opendir", OsConstants.EOPNOTSUPP)
            return fd
        } catch (error: Throwable) { fd.close(); throw error }
    }

    @Synchronized override fun list(path: String): Array<String> = operation("list") {
        val dir = resolve(parts(path))
        if (!dir.directory) fail("list", OsConstants.ENOTDIR)
        listAt(children(dir.id)).map { it.name }.filterNot(::isDetached).toTypedArray()
    }

    private fun create(parent: Entry, name: String, mime: String): Entry {
        if (!parent.directory) fail("create", OsConstants.ENOTDIR)
        if (parent.flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) fail("create", OsConstants.EACCES)
        var uri = DocumentsContract.createDocument(resolver, document(parent.id), mime, name) ?: fail("create", OsConstants.EIO)
        try {
            var created = lookup(uri)
            if (created.name == name) return created
            // Some Android releases append an extension when creating a file. Rename only our
            // own newly allocated document, and require the exact requested name.
            if (child(parent, name) != null) fail("create", OsConstants.EEXIST)
            uri = DocumentsContract.renameDocument(resolver, uri, name) ?: fail("create", OsConstants.EIO)
            created = lookup(uri)
            if (created.name != name) fail("create", OsConstants.EEXIST)
            return created
        } catch (error: Exception) {
            // Roll back only the allocated single entry. A directory concurrently populated
            // by another app must not be recursively removed by the provider.
            runCatching {
                val leftover = lookup(uri)
                unlink(parent, leftover.name, leftover.directory)
            }
            throw error
        }
    }

    @Synchronized override fun open(path: String, flags: Int, mode: Int): ParcelFileDescriptor = operation("open") {
        val access = flags and OsConstants.O_ACCMODE
        if (flags and directoryFlag != 0) {
            if (access != OsConstants.O_RDONLY || flags and (OsConstants.O_CREAT or OsConstants.O_TRUNC) != 0)
                fail("opendir", OsConstants.EINVAL)
            return@operation directoryFd(resolve(parts(path)))
        }
        if (path.isEmpty()) fail("open", OsConstants.EISDIR)
        val truncate = flags and OsConstants.O_TRUNC != 0
        val append = flags and OsConstants.O_APPEND != 0
        val requested = when (access) {
            OsConstants.O_RDONLY -> {
                if (truncate) fail("open", OsConstants.EOPNOTSUPP)
                "r"
            }
            OsConstants.O_WRONLY -> if (truncate) "wt" else "wa"
            OsConstants.O_RDWR -> if (truncate) "rwt" else "rw"
            else -> fail("open", OsConstants.EINVAL)
        }
        val (directory, name) = parent(path)
        val existing = child(directory, name)
        val file = if (existing != null) {
            if (flags and OsConstants.O_CREAT != 0 && flags and OsConstants.O_EXCL != 0) fail("open", OsConstants.EEXIST)
            existing
        } else {
            if (flags and OsConstants.O_CREAT == 0) fail("open", OsConstants.ENOENT)
            // The Android FUSE layer records the caller as owner on creation. Ownership
            // keeps subsequent renameat/unlinkat atomic for the project’s own output.
            try {
                val owned = directoryFd(directory).use { parentFd ->
                    val createFlags = access or OsConstants.O_CREAT or OsConstants.O_EXCL or
                        OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or (if (append) OsConstants.O_APPEND else 0)
                    Os.open("/proc/self/fd/${parentFd.fd}/$name", createFlags, mode and 511)
                }
                try { return@operation ParcelFileDescriptor.dup(owned) } finally { Os.close(owned) }
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EEXIST && flags and OsConstants.O_EXCL != 0) throw error
                if (error.errno !in setOf(OsConstants.EEXIST, OsConstants.EACCES, OsConstants.EPERM,
                        OsConstants.EOPNOTSUPP, OsConstants.ENOENT)) throw error
                val appeared = child(directory, name)
                if (appeared != null) {
                    if (flags and OsConstants.O_EXCL != 0) fail("open", OsConstants.EEXIST)
                    return@operation open(path, flags and OsConstants.O_CREAT.inv(), mode)
                }
            }
            try { create(directory, name, artifactMediaType(name, null)) }
            catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST || flags and OsConstants.O_EXCL != 0) throw error
                child(directory, name) ?: throw error
            }
        }
        if (file.directory) fail("open", OsConstants.EISDIR)
        val fd = resolver.openFileDescriptor(document(file.id), requested) ?: fail("open", OsConstants.EIO)
        try {
            if (!OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) fail("open", OsConstants.EOPNOTSUPP)
            // SAF "w" can truncate. Use "wa" for write-only opens, then restore the exact
            // Linux append flag and initial offset on this independent open file description.
            val adjusted = NativeProbe.checkDocumentOpenMode(fd.fd, access, append)
            if (adjusted != 0) fail("open", -adjusted)
            Os.lseek(fd.fileDescriptor, 0L, OsConstants.SEEK_SET)
            fd
        } catch (error: Throwable) { fd.close(); throw error }
    }

    @Synchronized override fun mkdir(path: String) = operation("mkdir") {
        if (path.isEmpty()) fail("mkdir", OsConstants.EEXIST)
        val (directory, name) = parent(path)
        if (child(directory, name) != null) fail("mkdir", OsConstants.EEXIST)
        create(directory, name, Document.MIME_TYPE_DIR)
        Unit
    }

    @Synchronized override fun remove(path: String, directory: Boolean) = operation("remove") {
        val (parent, name) = parent(path)
        val file = child(parent, name) ?: fail("remove", OsConstants.ENOENT)
        if (file.directory != directory) fail("remove", if (directory) OsConstants.ENOTDIR else OsConstants.EISDIR)
        if (directory && listAt(children(file.id)).isNotEmpty()) fail("rmdir", OsConstants.ENOTEMPTY)
        if (file.flags and Document.FLAG_SUPPORTS_DELETE == 0) fail("remove", OsConstants.EACCES)
        // A SAF document ID on local storage is name-based. A concurrent rename could turn
        // deleteDocument(file) into recursive deletion of a directory. Use unlinkat semantics.
        try { unlink(parent, name, directory) }
        catch (error: ErrnoException) {
            if (directory || error.errno !in setOf(OsConstants.EPERM, OsConstants.EACCES,
                    OsConstants.ENOENT, OsConstants.EOPNOTSUPP)) throw error
            removeForeignFile(parent, file)
        }
        Unit
    }

    private fun unlink(parent: Entry, name: String, directory: Boolean) {
        directoryFd(parent).use { handle ->
            val result = NativeProbe.unlinkDirectoryEntry(handle.fd, name, directory)
            if (result != 0) fail("remove", -result)
        }
    }

    private fun regularIdentity(handle: ParcelFileDescriptor): Pair<Long,Long> {
        val info = Os.fstat(handle.fileDescriptor)
        if (!OsConstants.S_ISREG(info.st_mode)) fail("unlink", OsConstants.EISDIR)
        return info.st_dev to info.st_ino
    }

    private fun clearRemoval(ticket: String) {
        if (!removals.edit().remove(ticket).commit()) fail("unlink journal", OsConstants.EIO)
    }

    /** ExternalStorageProvider can recursively delete directories. Never issue that request for a
     * user-supplied path. Capture the entry under an unguessable name hidden from the guest, then
     * verify its descriptor identifies the same regular file. An interrupted capture is replayable. */
    private fun removeForeignFile(parent: Entry, file: Entry) = synchronized(removalLock) {
        if (file.directory || file.flags and Document.FLAG_SUPPORTS_RENAME == 0) fail("unlink", OsConstants.EACCES)
        val original = resolver.openFileDescriptor(document(file.id), "r") ?: fail("unlink", OsConstants.ENOENT)
        original.use { handle ->
            val identity = regularIdentity(handle)
            val extension = file.name.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
            val ticket = detachedPrefix + UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: "")
            val state = JSONObject().put("tree", tree.toString()).put("rootDev", rootIdentity.first)
                .put("rootIno", rootIdentity.second).put("parent", parent.id).put("name", file.name)
                .put("dev", identity.first).put("ino", identity.second)
            if (!removals.edit().putString(ticket, state.toString()).commit()) fail("unlink journal", OsConstants.EIO)
            checkRoot()
            val moved = DocumentsContract.renameDocument(resolver, document(file.id), ticket)
            if (moved == null && child(parent, ticket) == null) {
                clearRemoval(ticket)
                fail("unlink", OsConstants.EIO)
            }
            completeRemoval(ticket, state)
        }
    }

    private fun completeRemoval(ticket: String, state: JSONObject) {
        checkRoot()
        val parent = lookup(document(state.getString("parent")))
        val detached = child(parent, ticket)
        if (detached == null) { clearRemoval(ticket); return }
        val expected = state.getLong("dev") to state.getLong("ino")
        val location = document(detached.id)
        val pinned = if (detached.directory) null else resolver.openFileDescriptor(location, "r")
        pinned.use { handle ->
            if (handle == null || regularIdentity(handle) != expected) {
                // If another app replaced the original just before detachment, preserve the
                // replacement. The provider's rename may uniquify when another original already exists.
                DocumentsContract.renameDocument(resolver, location, state.getString("name"))
                    ?: fail("restore", OsConstants.EIO)
                clearRemoval(ticket)
                fail("unlink", OsConstants.ESTALE)
            }
            checkRoot()
            if (!DocumentsContract.deleteDocument(resolver, location)) fail("unlink", OsConstants.EIO)
            clearRemoval(ticket)
        }
    }

    @Synchronized override fun rename(source: String, target: String, flags: Int) = operation("rename") {
        if (flags !in 0..1) fail("rename", OsConstants.EOPNOTSUPP)
        val (fromDir, fromName) = parent(source)
        val file = child(fromDir, fromName) ?: fail("rename", OsConstants.ENOENT)
        val (toDir, toName) = parent(target)
        if (source == target && flags == 0) return@operation
        if (file.directory && target.startsWith("$source/")) fail("rename", OsConstants.EINVAL)
        val existing = child(toDir, toName)
        if (existing != null && flags == 1) fail("rename", OsConstants.EEXIST)
        if (existing != null || (fromDir.id != toDir.id && fromName != toName)) {
            // Only the filesystem itself can atomically replace an existing target or do a
            // simultaneous rename and move. Scoped storage may refuse files owned by others.
            directoryFd(fromDir).use { old ->
                directoryFd(toDir).use { new ->
                    val result = NativeProbe.renameDirectoryEntry(old.fd, fromName, new.fd, toName, flags)
                    if (result != 0) fail("rename", -result)
                }
            }
            return@operation
        }
        val moved = if (fromDir.id == toDir.id) {
            if (file.flags and Document.FLAG_SUPPORTS_RENAME == 0) fail("rename", OsConstants.EACCES)
            DocumentsContract.renameDocument(resolver, document(file.id), toName)
        } else {
            if (fromName != toName || file.flags and Document.FLAG_SUPPORTS_MOVE == 0) fail("rename", OsConstants.EOPNOTSUPP)
            DocumentsContract.moveDocument(resolver, document(file.id), document(fromDir.id), document(toDir.id))
        } ?: fail("rename", OsConstants.EIO)
        if (lookup(moved).name != toName) {
            // A racing creator won the requested name; do not report a successful POSIX rename.
            if (fromDir.id == toDir.id) DocumentsContract.renameDocument(resolver, moved, fromName)
            else DocumentsContract.moveDocument(resolver, moved, document(toDir.id), document(fromDir.id))
            fail("rename", OsConstants.EEXIST)
        }
        Unit
    }

    private inline fun <T> operation(name: String, block: () -> T): T = try {
        checkRoot()
        val result = block()
        try { checkRoot() }
        catch (e: Exception) { if (result is ParcelFileDescriptor) result.close(); throw e }
        result
    }
    catch (error: ErrnoException) { throw error }
    catch (error: SecurityException) { throw ErrnoException(name, OsConstants.EACCES, error) }
    catch (error: FileNotFoundException) { throw ErrnoException(name, OsConstants.ENOENT, error) }
    catch (error: UnsupportedOperationException) { throw ErrnoException(name, OsConstants.EOPNOTSUPP, error) }
    catch (error: IOException) { throw ErrnoException(name, OsConstants.EIO, error) }
    catch (error: IllegalArgumentException) { throw ErrnoException(name, OsConstants.EINVAL, error) }

    private fun fail(name: String, errno: Int): Nothing = throw ErrnoException(name, errno)

    private companion object {
        const val detachedPrefix = ".dsh-unlink-"
        val removalLock = Any()
        val directoryFlag by lazy { NativeProbe.directoryOpenFlag() }
    }
}

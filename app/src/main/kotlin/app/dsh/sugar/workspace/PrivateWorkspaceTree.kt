package app.dsh.sugar.workspace

import app.dsh.sugar.runtime.SharedProjectTools
import com.sucrune.probe.NativeProbe
import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID

/** Match the runtime's private storage by filesystem identity, including Android's bind-mounted aliases. */
internal fun projectTreeComponents(filesDir: File, workspace: File): List<String> {
    require(workspace.isAbsolute && workspace.name == "workspace") { "无效的项目目录。" }
    val guest = workspace.parentFile
    val environment = guest?.parentFile
    if (guest != null && environment?.name == "sugar-retained" && retainedId.matches(guest.name) &&
        environment.parentFile?.name == "sucrune" && environment.parentFile.parentFile != null &&
        Files.isSameFile(filesDir.toPath(), environment.parentFile.parentFile.toPath())) {
        return listOf("sucrune", environment.name, guest.name, workspace.name)
    }
    val environments = environment?.parentFile
    val storage = environments?.parentFile
    val runtimeFiles = storage?.parentFile
    require(guest?.name == "workspace" && environment != null && projectEnvId.matches(environment.name) &&
        environments?.name == "environments" && storage?.name == "sucrune" &&
        runtimeFiles != null && Files.isSameFile(filesDir.toPath(), runtimeFiles.toPath())) {
        "无效的项目目录。"
    }
    return listOf(storage.name, environments.name, environment.name, guest.name, workspace.name)
}

private val projectEnvId = Regex("env_[0-9a-f]{32}")
private val retainedId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private val inodeId = Regex("[0-9a-f]{32}")

/** Pin each directory before opening its children. A guest may replace path components while a sync is running. */
internal class PrivateWorkspaceTree private constructor(
    context: Context, private val components: List<String>, private val inodeComponents: List<String>
) : FolderTree, Closeable {
    private constructor(context: Context, parts: List<String>) : this(context, parts,
        if (parts[1] == "sugar-retained") parts.dropLast(1) + "inodes"
        else parts.dropLast(2) + "workspace.sucrune-inodes-v1")
    constructor(context: Context, workspace: File) : this(context, projectTreeComponents(context.filesDir, workspace))
    private val deletionCache = context.cacheDir
    private val store = context.filesDir.toPath().resolve("sucrune/shared-tools-v1")
    private val privateRoot = context.filesDir
    private val root = openPrivateDirectory(components)
    companion object {
        /** The runtime supplies the control environment. Pin every component, including the guest root. */
        fun control(context: Context, guest: File): PrivateWorkspaceTree {
            val parts = projectTreeComponents(context.filesDir, File(guest, "workspace")).dropLast(1)
            require(parts[1] == "environments")
            return PrivateWorkspaceTree(context, parts, parts.dropLast(1) + "workspace.sucrune-inodes-v1")
        }
    }

    private fun openPrivateDirectory(parts: List<String>): ParcelFileDescriptor {
        var fd = openDirectory(privateRoot.path)
        try {
            for (component in parts) {
                val next = openDirectory(child(fd, component))
                fd.close()
                fd = next
            }
            return fd
        } catch (e: Throwable) { fd.close(); throw e }
    }

    private fun open(path: String, flags: Int, mode: Int = 0): ParcelFileDescriptor {
        val fd = Os.open(path, flags or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, mode)
        return try { ParcelFileDescriptor.dup(fd) } finally { Os.close(fd) }
    }
    private fun child(parent: ParcelFileDescriptor, name: String) = "/proc/self/fd/${parent.fd}/${checkedName(name)}"
    private fun openDirectory(path: String): ParcelFileDescriptor {
        check(OsConstants.S_ISDIR(Os.lstat(path).st_mode)) { "工作区路径包含非目录或符号链接。" }
        val fd = open(path,OsConstants.O_RDONLY)
        if (OsConstants.S_ISDIR(Os.fstat(fd.fileDescriptor).st_mode)) return fd
        fd.close()
        throw IOException("工作区目录在访问期间已更改。")
    }

    private fun <T> at(path: String, action: (String) -> T): T {
        val parts = path.split('/')
        var fd = ParcelFileDescriptor.dup(root.fileDescriptor)
        try {
            for (name in parts.dropLast(1)) {
                val next = openDirectory(child(fd, name))
                fd.close()
                fd = next
            }
            return action(child(fd, parts.last()))
        } finally { fd.close() }
    }

    private fun stat(location: String): android.system.StructStat {
        val entry = Os.lstat(location)
        if (OsConstants.S_ISLNK(entry.st_mode)) {
            reference(location)?.use { return Os.fstat(it.fileDescriptor) }
        }
        return entry
    }

    private fun readDescriptor(location: String): ParcelFileDescriptor {
        if (OsConstants.S_ISLNK(Os.lstat(location).st_mode)) {
            reference(location)?.let { return it }
        }
        val handle = open(location, OsConstants.O_RDONLY)
        try {
            check(OsConstants.S_ISREG(Os.fstat(handle.fileDescriptor).st_mode)) { "只可打开普通文件。" }
            return handle
        } catch (e: Throwable) { handle.close(); throw e }
    }

    /** Resolve Sucrune's manager-owned hard link bodies without following guest-controlled links. */
    private fun reference(location: String): ParcelFileDescriptor? {
        val marker = Os.readlink(location)
        if (!marker.startsWith("sucrune:inode:v1:")) return SharedProjectTools.openReference(store, marker)
        val id = marker.removePrefix("sucrune:inode:v1:")
        require(inodeId.matches(id))
        return openPrivateDirectory(inodeComponents).use { dir ->
            val owner = android.os.Process.myUid()
            val directory = Os.fstat(dir.fileDescriptor)
            check(directory.st_uid == owner && directory.st_mode and 511 == 448)
            check(!Files.exists(File(child(dir,"$id.dirty")).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val refs = open(child(dir,"$id.refs"),OsConstants.O_RDONLY)
            ParcelFileDescriptor.AutoCloseInputStream(refs).use { input ->
                val stat = Os.fstat(refs.fileDescriptor)
                check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == owner && stat.st_nlink == 1L &&
                    stat.st_size == 4L && stat.st_mode and 63 == 0)
                check(java.io.DataInputStream(input).readInt() != 0)
            }
            val body = open(child(dir,id),OsConstants.O_RDONLY)
            try {
                val stat = Os.fstat(body.fileDescriptor)
                check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == owner && stat.st_nlink == 1L &&
                    "%016x%016x".format(java.util.Locale.ROOT,stat.st_dev,stat.st_ino) == id)
                body
            } catch (error: Throwable) { body.close(); throw error }
        }
    }

    private fun input(location: String): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(readDescriptor(location))

    private fun stamp(location: String): String? {
        val stat = try { stat(location) } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return null else throw e
        }
        return when {
            OsConstants.S_ISDIR(stat.st_mode) -> DIRECTORY
            OsConstants.S_ISREG(stat.st_mode) -> input(location).use { transferDigest(it) }
            else -> UNSUPPORTED
        }
    }

    override fun scan(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        fun visit(fd: ParcelFileDescriptor, prefix: String) {
            val names = File("/proc/self/fd/${fd.fd}").list() ?: throw IOException("无法读取工作区目录。")
            for (name in names) {
                val location = child(fd, name)
                val path = prefix + name
                val kind = stamp(location) ?: throw IOException("同步期间工作区已更改，请重试。")
                out[path] = kind
                if (kind == DIRECTORY) openDirectory(location).use { visit(it, "$path/") }
            }
        }
        visit(root, "")
        return out
    }

    fun readOnlyDescriptor(relative: String): ParcelFileDescriptor = at(relative, ::readDescriptor)

    fun writableDocument(relative: String): Boolean = at(relative) { path ->
        val entry = Os.lstat(path)
        OsConstants.S_ISREG(entry.st_mode) && entry.st_nlink == 1L
    }

    /** Never truncate before fstat. Manager-owned reference symlinks and physical aliases stay read-only. */
    fun openWritableDocument(relative: String, mode: String): ParcelFileDescriptor = at(relative) { path ->
        val flags = when (mode) {
            "w", "wt" -> OsConstants.O_WRONLY
            "wa" -> OsConstants.O_WRONLY or OsConstants.O_APPEND
            "rw", "rwt" -> OsConstants.O_RDWR
            else -> throw IOException("无效的文件访问方式。")
        }
        check(writableDocument(relative))
        val fd = open(path, flags)
        try {
            val info = Os.fstat(fd.fileDescriptor)
            check(OsConstants.S_ISREG(info.st_mode) && info.st_nlink == 1L)
            if (mode in setOf("w", "wt", "rwt")) Os.ftruncate(fd.fileDescriptor, 0L)
            fd
        } catch (error: Throwable) { fd.close(); throw error }
    }

    fun createDocument(relative: String, directory: Boolean) = at(relative) { path ->
        if (directory) Os.mkdir(path, 448)
        else open(path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 384).close()
    }

    fun renameDocument(relative: String, newName: String) {
        val parent = relative.substringBeforeLast('/', "")
        inDocumentDirectory(parent) { dir ->
            val from = checkedName(relative.substringAfterLast('/'))
            val result = NativeProbe.renameDirectoryEntry(dir.fd, from, dir.fd, checkedName(newName), 1)
            if (result != 0) throw ErrnoException("rename", -result)
        }
    }

    fun documentStat(relative: String): android.system.StructStat =
        if (relative.isEmpty()) Os.fstat(root.fileDescriptor) else at(relative, ::stat)

    fun <T> inDocumentDirectory(relative: String, block: (ParcelFileDescriptor) -> T): T =
        if (relative.isEmpty()) block(root) else at(relative) { location -> openDirectory(location).use(block) }

    fun documentChildren(dir: ParcelFileDescriptor): List<Pair<String, android.system.StructStat>> {
        val names = File("/proc/self/fd/${dir.fd}").list() ?: throw IOException("无法读取工作区目录。")
        return names.mapNotNull { name ->
            if (runCatching { checkedName(name) }.isFailure) return@mapNotNull null
            try { name to stat(child(dir, name)) }
            catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) null else throw e }
        }
    }


    override fun read(path: String) = at(path, ::input)

    /** The callback must start its inotify watch before returning. The kernel then owns the inode reference. */
    fun visitDirectories(visit: (Pair<Long,Long>, File) -> Unit) {
        fun walk(fd: ParcelFileDescriptor) {
            val stat = Os.fstat(fd.fileDescriptor)
            val dir = File("/proc/self/fd/${fd.fd}")
            visit(stat.st_dev to stat.st_ino, dir)
            val names = dir.list() ?: throw IOException("无法监听工作区目录。")
            for (name in names) {
                val path = child(fd,name)
                val info = try { Os.lstat(path) } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.ENOENT) continue else throw e
                }
                if (OsConstants.S_ISDIR(info.st_mode)) openDirectory(path).use(::walk)
            }
        }
        walk(root)
    }

    override fun write(path: String, source: InputStream, digest: String, expected: String?) = at(path) { destination ->
        val temp = destination.substringBeforeLast('/') + "/.sugar-sync-" + UUID.randomUUID()
        val fd = open(temp, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY, 384)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output ->
                check(transferDigest(source, output) == digest) { "手机文件在同步期间已更改。" }
                if (expected != null) Os.fchmod(fd.fileDescriptor, stat(destination).st_mode and 511)
                output.fd.sync()
            }
            check(stamp(destination) == expected) { "工作区文件在同步期间已更改。" }
            Os.rename(temp, destination)
        } finally {
            try { Os.remove(temp) } catch (e: ErrnoException) { if (e.errno != OsConstants.ENOENT) throw e }
        }
    }

    override fun mkdir(path: String) = at(path) { Os.mkdir(it, 448) }

    override fun remove(path: String, expected: String) = at(path) {
        check(stamp(it) == expected) { "工作区文件在同步期间已更改。" }
        Os.remove(it)
    }

    fun deleteEntry(relative: String, didDelete: (String) -> Unit = {}) {
        at(relative) { location -> WorkspaceRemoval.remove(deletionCache, location, relative, didDelete) }
    }

    override fun close() = root.close()
}

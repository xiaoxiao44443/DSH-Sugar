package app.dsh.sugar.runtime

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.*
import com.sucrune.probe.GuestDirectory
import com.sucrune.probe.NativeProbe
import java.io.File
import java.nio.file.Path

/** A private scratch workspace with explicit repair targets. No control-root descriptor
 * or parent path is returned to the guest. Every ancestor is pinned without following links. */
internal class RecoveryDirectory(private val scratch: Path, private val control: Path) : GuestDirectory {
    private data class Entry(val root: Path, val relative: String, val zone: Int)
    private val config = "home/sugar/.dsh/profiles/web/cordis.patch.yml"
    companion object {
        private const val defaultProject = "deepseek-harness/default-workspace"
        private const val profileMount = "$defaultProject/repair/.dsh"
        const val profileMountPath = "/workspace/$profileMount"
    }
    private val directoryFlag = NativeProbe.directoryOpenFlag()
    init {
        java.nio.file.Files.createDirectory(scratch.resolve("repair"))
        java.nio.file.Files.createDirectories(scratch.resolve(profileMount))
    }

    // DSH starts a fresh profile in this subdirectory. Its normal workspace-write tools
    // can repair both targets there without requesting a wider DSH sandbox mode.
    private fun visiblePath(path: String): String =
        if (path == "$defaultProject/repair" || path.startsWith("$defaultProject/repair/"))
            path.removePrefix("$defaultProject/") else path

    private fun entry(guestPath: String): Entry {
        // Sucrune overlays this empty anchor with the original profile. The legacy
        // /workspace/repair alias still exposes its original program and config entries.
        if (guestPath == profileMount) return Entry(scratch, profileMount, 0)
        val path = visiblePath(guestPath)
        if (path.isNotEmpty() && path.split('/').any { it.isEmpty() || it == "." || it == ".." || '\u0000' in it })
            throw ErrnoException("recovery path", EINVAL)
        return when {
            path == "repair/dsh" || path.startsWith("repair/dsh/") -> Entry(control, "opt/dsh" + path.removePrefix("repair/dsh"), 1)
            path == "repair/cordis.patch.yml" -> Entry(control, config, 2)
            else -> Entry(scratch, path, 0)
        }
    }

    private fun <T> at(e: Entry, createParents: Boolean = false, op: (ParcelFileDescriptor, String) -> T): T {
        val parent = e.relative.substringBeforeLast('/', "")
        var dir = ProjectTreeCopy.directory(e.root.toString())
        try {
            if (parent.isNotEmpty()) for ((index, component) in parent.split('/').withIndex()) {
                val path = child(dir, component)
                val next = try { ProjectTreeCopy.directory(path) } catch (missing: ErrnoException) {
                    // Only provision the two manager-defined prefixes. Nested paths within
                    // the DSH installation retain normal mkdir/open semantics.
                    if (missing.errno != ENOENT || !createParents || !(e.zone == 2 || e.zone == 1 && index < 2)) throw missing
                    try { Os.mkdir(path, 448) } catch (race: ErrnoException) { if (race.errno != EEXIST) throw race }
                    ProjectTreeCopy.directory(path)
                }
                dir.close()
                dir = next
            }
            return op(dir, e.relative.substringAfterLast('/').ifEmpty { "." })
        } finally { dir.close() }
    }

    private fun child(fd: ParcelFileDescriptor, name: String) = "/proc/self/fd/${fd.fd}/$name"
    private fun pinDirectory(parent: ParcelFileDescriptor, name: String): ParcelFileDescriptor {
        val fd = Os.open(child(parent, name), O_RDONLY or O_NOFOLLOW or O_CLOEXEC or directoryFlag, 0)
        return try { ParcelFileDescriptor.dup(fd) } finally { Os.close(fd) }
    }
    private fun mutable(path: String) {
        if (path == profileMount || visiblePath(path) in setOf("", "repair", "repair/dsh")) throw ErrnoException("protected directory", EACCES)
    }
    private fun checkFile(mode: Int, links: Long) {
        if (!S_ISREG(mode) || links != 1L) throw ErrnoException("recovery file", EACCES)
    }

    override fun stat(path: String): LongArray = at(entry(path)) { parent, name ->
        val st = Os.lstat(child(parent, name))
        if (!S_ISDIR(st.st_mode)) checkFile(st.st_mode, st.st_nlink)
        longArrayOf(st.st_mode.toLong(), st.st_size, st.st_mtime * 1000, st.st_dev, st.st_ino)
    }
    override fun list(path: String): Array<String> {
        val names = at(entry(path)) { parent, name ->
            pinDirectory(parent, name).use { pinned ->
                File("/proc/self/fd/${pinned.fd}").list()?.toMutableSet() ?: throw ErrnoException("list", EIO)
            }
        }
        if (path == defaultProject) names.add("repair")
        if (path == "$defaultProject/repair") names.add(".dsh")
        if (visiblePath(path) == "repair") {
            for (target in listOf("dsh", "cordis.patch.yml")) {
                try { stat("repair/$target"); names.add(target) }
                catch (e: ErrnoException) { if (e.errno != ENOENT) throw e }
            }
        }
        return names.sorted().toTypedArray()
    }
    override fun open(path: String, flags: Int, mode: Int): ParcelFileDescriptor {
        if (flags and directoryFlag != 0) {
            // Sucrune keeps this descriptor in the broker to pin the cwd or an open directory.
            if (flags != (O_RDONLY or directoryFlag)) throw ErrnoException("opendir", EINVAL)
            return at(entry(path)) { parent, name -> pinDirectory(parent, name) }
        }
        return at(entry(path), createParents = flags and O_CREAT != 0) { parent, name ->
            if (visiblePath(path) == "repair/dsh") throw ErrnoException("open", EISDIR)
            val fd = Os.open(child(parent, name), (flags and O_TRUNC.inv()) or O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK, mode and 511)
            try {
                val st = Os.fstat(fd)
                checkFile(st.st_mode, st.st_nlink)
                if (flags and O_TRUNC != 0) Os.ftruncate(fd, 0)
                ParcelFileDescriptor.dup(fd)
            } finally { Os.close(fd) }
        }
    }
    @Synchronized override fun mkdir(path: String) {
        if (visiblePath(path) != "repair/dsh") mutable(path)
        val e = entry(path)
        if (e.zone == 2) throw ErrnoException("mkdir", EACCES)
        at(e, createParents = true) { parent, name -> Os.mkdir(child(parent, name), 448) }
    }
    @Synchronized override fun remove(path: String, directory: Boolean) {
        mutable(path)
        at(entry(path)) { parent, name ->
            val leaf = child(parent, name)
            if (S_ISDIR(Os.lstat(leaf).st_mode) != directory)
                throw ErrnoException("remove", if (directory) ENOTDIR else EISDIR)
            Os.remove(leaf)
        }
    }
    @Synchronized override fun rename(source: String, target: String, flags: Int) {
        mutable(source)
        mutable(target)
        val from = entry(source)
        val to = entry(target)
        // A profile editor may publish a temporary regular file next to cordis.patch.yml.
        if (from.zone != to.zone && !(from.zone == 0 && to.zone == 2 && visiblePath(source).substringBeforeLast('/', "") == "repair"))
            throw ErrnoException("rename", EXDEV)
        if (from.zone != to.zone) {
            val st = stat(source)
            if (!S_ISREG(st[0].toInt())) throw ErrnoException("rename", EACCES)
        }
        at(from) { old, name -> at(to, createParents = true) { new, dest ->
            val result = NativeProbe.renameDirectoryEntry(old.fd, name, new.fd, dest, flags)
            if (result != 0) throw ErrnoException("rename", -result)
        } }
    }
}

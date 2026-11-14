package app.dsh.sugar.runtime

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Capture a legacy guest project without following an ancestor which the guest can rename or replace. */
internal object ProjectTreeCopy {
    fun openSource(controlRoot: Path, oldRoot: String): ParcelFileDescriptor {
        var dir = directory(controlRoot.toString())
        try {
            for (part in oldRoot.substring(1).split('/')) {
                val next = directory(child(dir, part))
                dir.close()
                dir = next
            }
            return dir
        } catch (e: Throwable) {
            dir.close()
            throw e
        }
    }
    fun copy(controlRoot: Path, oldRoot: String, destination: Path) {
        val sourceRoot = Paths.get(oldRoot)
        fun snapshot(target: Path?): ByteArray = openSource(controlRoot, oldRoot).use { dir ->
            val hash = MessageDigest.getInstance("SHA-256")
            walk(dir, Paths.get(""), target, sourceRoot, hash, controlRoot.parent.parent.parent.resolve("shared-tools-v1"))
            hash.digest()
        }
        check(snapshot(destination).contentEquals(snapshot(null))) {
            "原工作区在迁移期间发生了变化。原文件已保留，请在写入完成后重试。"
        }
    }

    private fun child(parent: ParcelFileDescriptor, name: String) = "/proc/self/fd/${parent.fd}/$name"
    internal fun directory(path: String): ParcelFileDescriptor {
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        return try {
            require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "工作区路径必须是实际目录。" }
            ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
    }

    private fun note(hash: MessageDigest, text: String) {
        hash.update(text.toByteArray())
        hash.update(0)
    }

    private fun identity(stat: StructStat): String =
        "${stat.st_dev}:${stat.st_ino}:${stat.st_mode}:${stat.st_size}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"

    private fun walk(dir: ParcelFileDescriptor, relative: Path, to: Path?, root: Path, hash: MessageDigest, store: Path) {
        val dirStat = Os.fstat(dir.fileDescriptor)
        note(hash, identity(dirStat))
        val names = File("/proc/self/fd/${dir.fd}").list()?.sorted() ?: error("不能列出原工作区目录。")
        for (name in names) {
            val path = child(dir, name)
            val next = relative.resolve(name)
            val dest = to?.resolve(name)
            val entry = Os.lstat(path)
            note(hash, next.toString())
            note(hash, identity(entry))
            when {
                OsConstants.S_ISDIR(entry.st_mode) -> directory(path).use { sub ->
                    check(identity(Os.fstat(sub.fileDescriptor)) == identity(entry))
                    if (dest != null) Files.createDirectory(dest)
                    walk(sub, next, dest, root, hash, store)
                }
                OsConstants.S_ISREG(entry.st_mode) && dest != null -> {
                    val flags = OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
                    val fd = Os.open(path, flags, 0)
                    try {
                        check(identity(Os.fstat(fd)) == identity(entry))
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd)).use { input ->
                            Files.newOutputStream(dest, CREATE_NEW).use { output -> check(input.copyTo(output) == entry.st_size) }
                        }
                        check(identity(Os.fstat(fd)) == identity(entry)) { "原工作区的文件正在被修改。" }
                    } finally { Os.close(fd) }
                    Os.chmod(dest.toString(), entry.st_mode and 511)
                    Files.setLastModifiedTime(dest, java.nio.file.attribute.FileTime.fromMillis(entry.st_mtime * 1000))
                }
                OsConstants.S_ISLNK(entry.st_mode) -> {
                    val raw = Os.readlink(path)
                    val body = SharedProjectTools.openReference(store, raw)
                    if (body != null) {
                        body.use {
                            val stat = Os.fstat(it.fileDescriptor)
                            note(hash, raw)
                            if (dest != null) {
                                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(it.fileDescriptor)).use { input ->
                                    Files.newOutputStream(dest, CREATE_NEW).use { output -> check(input.copyTo(output) == stat.st_size) }
                                }
                                Os.chmod(dest.toString(), stat.st_mode and 511)
                                Files.setLastModifiedTime(dest, java.nio.file.attribute.FileTime.fromMillis(stat.st_mtime * 1000))
                            }
                        }
                        continue
                    }
                    check(!raw.startsWith("sucrune:inode:v1:")) { "原工作区含有托管硬链接，需要先导出独立副本。原文件已保留。" }
                    note(hash, raw)
                    val parent = root.resolve(relative)
                    val normalized = parent.resolve(raw).normalize()
                    require(normalized.startsWith(root)) { "原工作区含有指向项目外的符号链接。原文件已保留。" }
                    if (dest != null) {
                        val target = if (raw.startsWith('/')) parent.relativize(normalized).toString().ifEmpty { "." } else raw
                        Files.createSymbolicLink(dest, Paths.get(target))
                    }
                }
                !OsConstants.S_ISREG(entry.st_mode) -> error("原工作区包含特殊文件，无法自动迁移。原文件已保留。")
            }
        }
        check(identity(Os.fstat(dir.fileDescriptor)) == identity(dirStat)) { "原工作区目录在迁移期间发生了变化。" }
    }
}

package app.dsh.sugar.workspace

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.UUID

/** A rename in the same filesystem removes the selected entry from the workspace atomically.
 * Detach nested directories into the app-only staging area before traversing them. A guest
 * retaining a descriptor inside the old tree cannot swap a symlink into a traversed path.
 */
internal object WorkspaceRemoval {
    @Synchronized
    fun remove(cache: File, path: String, relative: String, didDelete: (String) -> Unit) {
        relative.split('/').forEach(::checkedName)
        val stage = File(cache, "removed-workspace-entries")
        Files.createDirectories(stage.toPath())
        val held = File(stage, UUID.randomUUID().toString())
        Os.rename(path, held.path)
        didDelete(relative)
        drain(stage, held, relative, didDelete)
    }

    /** Retry physical cleanup if Android stopped the process after the atomic rename. */
    @Synchronized
    fun sweep(cache: File) {
        val stage = File(cache, "removed-workspace-entries")
        for (file in stage.listFiles().orEmpty()) {
            runCatching { drain(stage, file, "") {} }
        }
    }

    private fun drain(stage: File, held: File, relative: String, didDelete: (String) -> Unit) {
        val directories = ArrayDeque<Pair<File,String>>()
        directories.add(held to relative)
        while (directories.isNotEmpty()) {
            val (current, prefix) = directories.removeLast()
            val mode = Os.lstat(current.path).st_mode
            if (!OsConstants.S_ISDIR(mode)) {
                Os.remove(current.path)
                continue
            }
            // The entry is now detached and accessible only under the app-owned staging path.
            if (mode and 448 != 448) Os.chmod(current.path, mode or 448)
            val names = current.list() ?: throw IOException("无法列出已删除的目录。")
            for (name in names) {
                val entry = File(current, name)
                val child = if (prefix.isEmpty()) name else "$prefix/$name"
                try {
                    // Don't trust a stat of a guest-controlled child across another syscall.
                    // remove doesn't follow a link. Pin nonempty directories by renaming.
                    Os.remove(entry.path)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.ENOENT) continue
                    if (e.errno != OsConstants.ENOTEMPTY && e.errno != OsConstants.EEXIST) throw e
                    val next = File(stage, UUID.randomUUID().toString())
                    Os.rename(entry.path, next.path)
                    directories.add(next to child)
                }
                didDelete(child)
            }
            Os.remove(current.path)
        }
    }
}

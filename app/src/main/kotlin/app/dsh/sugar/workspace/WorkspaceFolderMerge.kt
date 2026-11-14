package app.dsh.sugar.workspace

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val DIRECTORY = "dir"
internal const val UNSUPPORTED = "unsupported"

internal interface FolderTree {
    fun scan(): Map<String, String>
    fun read(path: String): InputStream
    fun write(path: String, source: InputStream, digest: String, expected: String?)
    fun mkdir(path: String)
    fun remove(path: String, expected: String)
}

internal fun checkedName(name: String): String {
    require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code == 0 }) {
        "提供方返回了无法用于 Linux 工作区的文件名。"
    }
    return name
}

internal fun transferDigest(input: InputStream, output: OutputStream? = null): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        digest.update(buffer, 0, count)
        output?.write(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class SyncReport(val transferred: Int, val conflicts: List<String>)

/** The baseline records confirmed common contents. A conflict leaves both originals intact. */
internal fun mergeWorkspaceFolders(
    local: FolderTree, phone: FolderTree, baseline: MutableMap<String, String>,
    choice: Pair<String, Boolean>? = null
): SyncReport {
    val disk = local.scan()
    val remote = phone.scan()
    val paths = (disk.keys + remote.keys + baseline.keys).sorted()
    val conflicts = linkedSetOf<String>()
    val deferred = mutableListOf<Pair<String, () -> Unit>>()
    var changed = 0
    fun within(child: String, root: String) = child == root || child.startsWith("$root/")
    fun prefixConflict(path: String): Boolean {
        var p = path
        while ('/' in p) {
            p = p.substringBeforeLast('/')
            if (p in conflicts) return true
        }
        return false
    }
    // The deleted side may have removed a whole directory. Protect the surviving
    // directory, including otherwise unchanged siblings, if any descendant diverged.
    val old = baseline.toMap()
    fun changedDirectories(snapshot: Map<String,String>): Set<String> {
        val directories = mutableSetOf<String>()
        for (path in paths) if (snapshot[path] != old[path]) {
            var p = path
            while ('/' in p) {
                p = p.substringBeforeLast('/')
                if (!directories.add(p)) break
            }
        }
        return directories
    }
    val changedLocal = changedDirectories(disk)
    val changedPhone = changedDirectories(remote)
    for (path in paths) {
        if (prefixConflict(path)) continue
        val left = disk[path]
        val right = remote[path]
        val before = old[path]
        if (left == UNSUPPORTED || right == UNSUPPORTED) { conflicts.add(path); continue }
        if (left == right) {
            if (left == null) baseline.remove(path) else baseline[path] = left
            continue
        }
        val manual = choice?.takeIf { within(path, it.first) }?.second
        val survivorChanges = if (left == null) changedPhone else changedLocal
        if (manual == null && before == DIRECTORY && (left == null || right == null) &&
                path in survivorChanges) {
            conflicts.add(path)
            continue
        }
        val fromPhone = manual ?: when {
            left == before -> true
            right == before -> false
            else -> { conflicts.add(path); continue }
        }
        val target = if (fromPhone) local else phone
        val source = if (fromPhone) phone else local
        val desired = if (fromPhone) right else left
        val existing = if (fromPhone) left else right
        if (existing != null && desired != null && (desired == DIRECTORY || existing == DIRECTORY)) {
            // A file and a directory cannot occupy one name. Keep both until one is renamed.
            conflicts.add(path)
            continue
        }
        if (desired == null) {
            val remove = {
                target.remove(path, checkNotNull(existing))
                baseline.remove(path)
                changed++
                Unit
            }
            if (existing == DIRECTORY) deferred.add(path to remove) else remove()
        } else {
            if (desired == DIRECTORY) target.mkdir(path)
            else source.read(path).use { target.write(path, it, desired, existing) }
            baseline[path] = desired
            changed++
        }
    }
    deferred.asReversed().forEach { (path, remove) ->
        if (conflicts.none { within(it,path) }) remove() else conflicts.add(path)
    }
    return SyncReport(changed, conflicts.toList())
}

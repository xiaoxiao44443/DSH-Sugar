package app.dsh.sugar.runtime

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/** Decide from a single registration/mount snapshot. A detached row may already be re-registered
 * before its first worker is opened, and a new directory can have the same textual path. */
internal fun canRemoveRetainedRoot(id: String, path: String, registered: Set<String>, pending: Set<String>,
                                   mounts: Map<String, String?>): Boolean {
    if (id in registered || id in pending || mounts[path] != id || !isWorkspaceRoot(path)) return false
    if (path.startsWith("/workspace/deepseek-harness/") ||
        Regex("/workspace/(?:phone|local)-[0-9a-f]{64}(?:/.*)?").matches(path)) return false
    return mounts.keys.none { other -> other != path && (other.startsWith("$path/") || path.startsWith("$other/")) }
}

internal object RetainedRootDeletion {
    const val CONTROL = "original-directory"

    /** Source is addressed through its pinned parent fd. Compare the moved inode as well: a guest
     * may have swapped the directory after we advertised FLAG_SUPPORTS_DELETE. */
    fun stageOriginal(source: Path, staged: Path, expected: String, identity: (Path) -> String?): Boolean {
        if (!Files.exists(staged, NOFOLLOW_LINKS)) {
            if (identity(source) != expected) return false
            Files.move(source, staged, ATOMIC_MOVE)
        }
        if (identity(staged) == expected) return true
        // This entry belongs to somebody else. NEVER drain it. Leave a same-name replacement intact.
        try {
            Files.move(staged, source)
        } catch (_: FileAlreadyExistsException) {
            Files.move(staged, source.resolveSibling("recovered-" + UUID.randomUUID()))
        }
        return false
    }

    fun relativeDocument(path: String): String? {
        for (tree in listOf("archive/workspace", "archive/$CONTROL")) {
            if (path == tree) return ""
            if (path.startsWith("$tree/")) return path.removePrefix("$tree/")
        }
        return null
    }
}

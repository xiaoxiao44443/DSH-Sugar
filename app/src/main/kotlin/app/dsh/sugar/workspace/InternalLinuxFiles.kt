package app.dsh.sugar.workspace

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.system.StructStat
import app.dsh.sugar.runtime.ProjectSandboxes
import java.io.File
import java.io.FileNotFoundException

/** Virtual view of the control Linux filesystem with project data mounted at its original path. */
internal class InternalLinuxFiles(context: Context) {
    private val app = context.applicationContext

    data class Entry(val name: String, val stat: StructStat?, val writable: Boolean,
                     val creatable: Boolean, val deletable: Boolean, val renamable: Boolean)

    fun stat(parts: List<String>): Entry = at(parts) { view, path ->
        if (view.mounts[path] != null) view.entry(path, null)
        else view.access(path) { tree, relative ->
            val file = tree.documentStat(relative)
            view.entry(path, file, OsConstants.S_ISREG(file.st_mode) && view.userArea(path) && tree.writableDocument(relative))
        }
    }

    fun list(parts: List<String>, watch: (File) -> Unit = {}): List<Entry> = at(parts) { view, path ->
        if (path in pseudoRoots) return@at emptyList()
        view.access(path) { tree, relative ->
            tree.inDocumentDirectory(relative) { dir ->
                watch(File("/proc/self/fd/${dir.fd}"))
                val children = LinkedHashMap<String, Entry>()
                val prefix = if (path == "/") "/" else "$path/"
                for ((name, file) in tree.documentChildren(dir)) {
                    val child = prefix + name
                    if (name.any(Char::isISOControl) || !view.visible(child) || !supported(file)) continue
                    if (view.mounts.containsKey(child)) continue
                    val item = if (relative.isEmpty()) name else "$relative/$name"
                    val mutable = OsConstants.S_ISREG(file.st_mode) && view.userArea(child) && tree.writableDocument(item)
                    children[name] = view.entry(child, file, mutable)
                }
                for ((mounted, owner) in view.mounts) {
                    if (owner == null || !mounted.startsWith(prefix) || !view.visible(mounted)) continue
                    val name = mounted.removePrefix(prefix)
                    if ('/' !in name && name.isNotEmpty()) children[name] = view.entry(mounted, null)
                }
                children.values.toList()
            }
        }
    }

    fun open(parts: List<String>, mode: String): ParcelFileDescriptor = at(parts) { view, path ->
        require(parts.isNotEmpty() && !view.mounts.containsKey(path))
        view.access(path) { tree, relative ->
            if (mode == "r") tree.readOnlyDescriptor(relative)
            else {
                require(view.userArea(path))
                tree.openWritableDocument(relative, mode)
            }
        }
    }

    fun create(parent: List<String>, name: String, directory: Boolean) {
        at(parent + name) { view, path ->
            require(view.userArea(fullPath(parent)) && view.userArea(path) && !view.mounts.containsKey(path))
            view.access(path) { tree, relative -> tree.createDocument(relative, directory) }
        }
    }

    fun rename(parts: List<String>, newName: String) {
        at(parts) { view, path ->
            val target = fullPath(parts.dropLast(1) + newName)
            require(view.unmountedEntry(path) && view.unmountedEntry(target) && view.visible(target))
            view.access(path) { tree, relative -> tree.renameDocument(relative, newName) }
        }
    }

    fun delete(
        parts: List<String>,
        didDelete: (absoluteParts: List<String>, projectId: String?, relativeParts: List<String>) -> Unit = { _, _, _ -> }
    ) {
        at(parts) { view, path ->
            if (view.detachedRoot(path)) {
                val id = requireNotNull(view.mounts[path])
                ProjectSandboxes.deleteRetainedDocument(app, id, path) { removed ->
                    val relative = if (removed.isEmpty()) emptyList() else removed.split('/')
                    didDelete(parts + relative, id, relative)
                }
            } else {
                require(view.unmountedEntry(path))
                val id = view.mount(path)?.let { view.mounts[it] }
                view.access(path) { tree, relative ->
                    val base = parts.dropLast(relative.split('/').size)
                    tree.deleteEntry(relative) { removed ->
                        val child = removed.split('/')
                        didDelete(base + child, id, if (id != null) child else emptyList())
                    }
                }
            }
        }
    }

    private fun <T> at(parts: List<String>, block: (View, String) -> T): T {
        val path = fullPath(parts)
        return ProjectSandboxes.withInternalDocuments(app) { guest, mounts, deletableRetainedPaths ->
            val view = View(guest, mounts, deletableRetainedPaths)
            if (!view.visible(path)) throw FileNotFoundException()
            block(view, path)
        }
    }

    private inner class View(val guest: File, val mounts: Map<String, String?>, val deletableRetainedPaths: Set<String>) {
        fun visible(path: String): Boolean {
            if (pseudoRoots.any { path.startsWith("$it/") } || privatePaths.any { below(path, it) }) return false
            return mounts.none { (mount, id) -> id == null && below(path, mount) }
        }

        fun userArea(path: String): Boolean {
            if (mounts.any { (root, id) -> id != null && below(path, root) }) return true
            return when {
                path == "/workspace" || path == "/home" || path == "/home/sugar" -> true
                below(path, "/workspace/deepseek-harness") -> false
                path.startsWith("/home/sugar/.") || path.startsWith("/home/.") || path.startsWith("/workspace/.") -> false
                else -> path.startsWith("/home/") || path.startsWith("/workspace/")
            }
        }

        fun unmountedEntry(path: String): Boolean = path !in containers && userArea(path) &&
            mounts.keys.none { below(it, path) }

        fun detachedRoot(path: String): Boolean = path in deletableRetainedPaths &&
            mounts.keys.none { it.startsWith("$path/") }

        fun entry(path: String, info: StructStat?, writable: Boolean = false): Entry {
            require(info == null || supported(info))
            val directory = info == null || OsConstants.S_ISDIR(info.st_mode)
            val ordinary = unmountedEntry(path)
            return Entry(path.substringAfterLast('/').ifEmpty { "/" }, info, writable,
                directory && userArea(path), ordinary || detachedRoot(path), ordinary)
        }

        fun mount(path: String): String? = mounts.keys.filter { below(path, it) }.maxByOrNull { it.length }

        fun <T> access(path: String, action: (PrivateWorkspaceTree, String) -> T): T {
            val mount = mount(path)
            if (mount != null) {
                val owner = mounts[mount] ?: throw FileNotFoundException()
                return ProjectSandboxes.withDocumentProject(app, owner) { _, folder ->
                    PrivateWorkspaceTree(app, folder).use { action(it, path.removePrefix(mount).trimStart('/')) }
                }
            }
            return PrivateWorkspaceTree.control(app, guest).use { action(it, path.drop(1)) }
        }
    }

    companion object {
        private val pseudoRoots = setOf("/dev", "/proc", "/sys")
        private val containers = setOf("/", "/home", "/home/sugar", "/workspace", "/workspace/deepseek-harness")
        private val privatePaths = listOf("/home/sugar/.ssh", "/home/sugar/.gnupg", "/home/sugar/.netrc",
            "/home/sugar/.git-credentials", "/opt/sugar/workspace-bridge.json")

        private fun below(path: String, parent: String) = path == parent || path.startsWith("$parent/")
        private fun supported(file: StructStat) = OsConstants.S_ISDIR(file.st_mode) || OsConstants.S_ISREG(file.st_mode)
        private fun fullPath(parts: List<String>): String {
            require(parts.size < 2048)
            parts.forEach { require(checkedName(it).toByteArray().size <= 255 && it.none(Char::isISOControl)) }
            return parts.joinToString("/", prefix = "/").also { require(it.toByteArray().size <= 4095) }
        }
    }
}

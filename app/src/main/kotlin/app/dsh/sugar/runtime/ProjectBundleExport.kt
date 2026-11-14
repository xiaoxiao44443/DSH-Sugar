package app.dsh.sugar.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import app.dsh.sugar.workspace.LocalFolderWorkspaces
import app.dsh.sugar.workspace.LocalDocumentDirectory
import app.dsh.sugar.workspace.checkedName
import android.system.Os
import com.sucrune.probe.NativeProbe
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

/** Keep a stable source for pnpm's local dependencies when the originating project is removed. */
internal object ProjectBundleExport {
    private const val parent = "/home/sugar/.dsh"
    private const val store = "sugar-plugin-sources"
    private val published = Regex("$parent/$store/[0-9a-f]{64}(?:-[0-9]+)?")
    private val created = Regex("$parent/profiles/web/created-plugins/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    internal fun isSharedBundleSource(path: String): Boolean = published.matches(path) || created.matches(path)

    @Synchronized
    fun publish(context: Context, controlId: String, project: ProjectSandbox, input: String): String {
        val fromProfile = isSharedBundleSource(input)
        require((input.startsWith("/workspace/") || fromProfile) && input.length <= 4095 &&
            input.drop(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) })
            { "请选择当前工作区内的插件目录或已发布的全局插件源码目录。" }
        val response = JSONObject(NativeProbe.coreCall(File(context.filesDir, "sucrune").path,
            JSONObject().put("op", "env.paths").put("env_id", controlId).toString()))
        check(response.getBoolean("ok"))
        val control = File(response.getJSONObject("result").getString("workspace")).toPath()
        ProjectTreeCopy.openSource(control, parent).use { home ->
            val dir = File("/proc/self/fd/${home.fd}", store).toPath()
            if (!Files.exists(dir, NOFOLLOW_LINKS)) Files.createDirectory(dir)
        }
        ProjectTreeCopy.openSource(control, "$parent/$store").use { handle ->
            val directory = File("/proc/self/fd/${handle.fd}").toPath()
            val stage = Files.createTempDirectory(directory, ".pending-")
            try {
                if (fromProfile) {
                    ProjectTreeCopy.copy(control, input, stage)
                } else {
                    val tree = LocalFolderWorkspaces.treeFor(context, project.workspaceId, project.root)
                    if (tree == null) ProjectTreeCopy.copy(project.workspace.parentFile!!.toPath(), input, stage)
                    else copyLocalBundle(LocalDocumentDirectory(context, tree), input.removePrefix("/workspace/"), stage)
                }
                require(Files.isRegularFile(stage.resolve("package.json"), NOFOLLOW_LINKS)) { "插件目录缺少 package.json。" }
                val hash = digest(stage)
                // A profile editor may have modified a previously published copy that is still in use.
                // Keep that install intact and store the requested revision beside it.
                var target = directory.resolve(hash)
                var revision = 2
                while (Files.exists(target, NOFOLLOW_LINKS)) {
                    if (Files.isDirectory(target, NOFOLLOW_LINKS) && digest(target) == hash)
                        return "$parent/$store/${target.fileName}"
                    target = directory.resolve("$hash-${revision++}")
                }
                Files.move(stage, target, ATOMIC_MOVE)
                return "$parent/$store/${target.fileName}"
            } finally {
                GuestImage.removeTree(stage)
            }
        }
    }

    /** Publishing a plugin explicitly takes a snapshot, including when its source is on the phone. */
    private fun copyLocalBundle(directory: LocalDocumentDirectory, source: String, destination: Path) {
        val names = directory.list(source).sorted()
        for (name in names) {
            val target = destination.resolve(checkedName(name))
            val path = "$source/$name"
            val before = directory.stat(path)
            if (OsConstants.S_ISDIR(before[0].toInt())) {
                Files.createDirectory(target)
                copyLocalBundle(directory, path, target)
            } else {
                check(OsConstants.S_ISREG(before[0].toInt())) { "插件目录里包含无法发布的文件。" }
                ParcelFileDescriptor.AutoCloseInputStream(directory.open(path, OsConstants.O_RDONLY, 0)).use {
                    check(Files.copy(it, target) == before[1]) { "插件文件在发布时发生了变化。" }
                }
            }
            check(before.contentEquals(directory.stat(path))) { "插件文件在发布时发生了变化。" }
        }
        check(names == directory.list(source).sorted()) { "插件目录在发布时发生了变化。" }
    }

    private fun digest(root: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val data = value.toByteArray()
            hash.update(ByteBuffer.allocate(4).putInt(data.size).array())
            hash.update(data)
        }
        val canonical = root.toRealPath()
        Files.walk(root).use { paths ->
            paths.filter { it != root }.sorted().forEach { path ->
                field(root.relativize(path).toString())
                when {
                    Files.isSymbolicLink(path) -> {
                        check(path.toRealPath().startsWith(canonical)) { "插件包含失效或指向目录外的链接。" }
                        field("link")
                        field(Files.readSymbolicLink(path).toString())
                    }
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> field("directory")
                    else -> {
                        field("file")
                        field((Os.stat(path.toString()).st_mode and 511).toString())
                        hash.update(ByteBuffer.allocate(8).putLong(Files.size(path)).array())
                        val bytes = ByteArray(65536)
                        Files.newInputStream(path).use { stream ->
                            while (true) {
                                val n = stream.read(bytes)
                                if (n < 0) break
                                hash.update(bytes, 0, n)
                            }
                        }
                    }
                }
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

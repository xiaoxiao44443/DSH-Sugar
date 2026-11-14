package app.dsh.sugar.runtime

import app.dsh.sugar.R
import app.dsh.sugar.sugarString
import android.content.Context
import com.sucrune.probe.NativeProbe
import com.sucrune.probe.RuntimeClient
import org.json.JSONObject
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Installs the image from this signed APK before starting a worker. */
internal object GuestImage {
    internal val systemRoots = setOf("bin", "sbin", "lib", "lib64", "usr", "opt", "etc", "share", "var")
    private const val resolverPath = "lib/aarch64-linux-gnu/libresolv.so.2"
    private const val resolverSize = 68232L
    // Same Debian bookworm ARM64 libc6 2.36-9+deb12u14 as the control image loader.
    private const val resolverSha256 = "5f3769c75e5eca6ff14f2c7a22bcc8cfeeef31da9545434a281a5fdd1f66d6f0"

    fun prepare(context: Context, client: RuntimeClient, onProgress: (String) -> Unit): String {
        // The host owns one DSH project. Looking it up by a private, stable name also
        // recovers a crash after env.create and before the first image was unpacked.
        val environments = client.listEnvironments()
        val project = (0 until environments.length()).map { environments.getJSONObject(it) }
            .singleOrNull { it.getString("name") == "DSH Sugar" }
            ?: client.createEnvironment("DSH Sugar")
        RecoverySession.finishProgramRestore(root(context, project.getString("id")))
        return prepare(context, client, project, onProgress)
    }

    internal fun root(context: Context, id: String): Path {
        val request = JSONObject().put("op", "env.paths").put("env_id", id)
        val paths = JSONObject(NativeProbe.coreCall(File(context.filesDir, "sucrune").path, request.toString()))
        check(paths.getBoolean("ok")) { "无法打开 Sucrune 工作区" }
        return File(paths.getJSONObject("result").getString("workspace")).toPath()
    }

    internal fun bundle(context: Context): JSONObject = context.assets.open("guest/bundle.json").bufferedReader().use {
        JSONObject(it.readText()).also { data ->
            require(data.getInt("schema") == 1 && data.getString("sha256").matches(Regex("[a-f0-9]{64}"))) { "系统镜像格式不受支持" }
        }
    }

    internal fun prepare(context: Context, client: RuntimeClient, project: JSONObject, onProgress: (String) -> Unit,
                         bundle: JSONObject = bundle(context)): String {
        val id = project.getString("id")
        val workspace = root(context, id)
        val parent = workspace.parent
        val receipt = parent.resolve("sugar-image.sha256")
        val expected = bundle.getString("sha256")
        if (!Files.isRegularFile(receipt, NOFOLLOW_LINKS) || receipt.toFile().readText() != expected) {
            check(project.getString("state") != "running") { "项目仍有进程在运行，暂时无法更新系统镜像" }
            replaceBase(context, bundle, workspace, receipt, onProgress)
        }
        installImageResolver(context, client, id, workspace)
        NetworkTools.install(context, client, id, parent, expected)
        return id
    }

    /** Sharp's libvips loads in the control environment after the project worker supplies the image bytes. */
    private fun installImageResolver(context: Context, client: RuntimeClient, id: String, workspace: Path) {
        val library = resolverPath.substringAfterLast('/')
        ProjectTreeCopy.openSource(workspace, "/" + resolverPath.substringBeforeLast('/')).use { directory ->
            val path = Paths.get("/proc/self/fd/${directory.fd}/$library")
            if (Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == resolverSize) {
                val digest = Files.newInputStream(path, NOFOLLOW_LINKS).use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
                if (digest.joinToString("") { "%02x".format(it) } == resolverSha256) return
            }
        }
        // Reuse the signed APK's copy on both fresh and previously provisioned devices.
        // RuntimeClient checks its length and digest before atomically publishing it.
        ZipInputStream(context.assets.open("mobile-python/runtime.zip").buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("Missing ARM64 image codec dependency: $resolverPath")
                if (entry.name == resolverPath) {
                    client.importFile(id, "/$resolverPath", zip, resolverSize, resolverSha256)
                    return
                }
            }
        }
    }

    private fun replaceBase(context: Context, bundle: JSONObject, workspace: Path, receipt: Path, onProgress: (String) -> Unit) {
        val parent = workspace.parent
        val stage = parent.resolve("sugar-image.pending")
        val previous = parent.resolve("sugar-image.previous")
        removeTree(stage)
        Files.createDirectory(stage)
        try {
            unpack(context, bundle, stage, onProgress)
            // Replacing bundled system directories leaves /home, /workspace and /tmp intact.
            // An interrupted publish has no receipt and is safely repeated before the next boot.
            Files.deleteIfExists(receipt)
            removeTree(previous)
            Files.createDirectory(previous)
            Files.newDirectoryStream(stage).use { entries ->
                for (entry in entries) {
                    val name = entry.fileName
                    val target = workspace.resolve(name)
                    if (Files.exists(target, NOFOLLOW_LINKS)) Files.move(target, previous.resolve(name), ATOMIC_MOVE)
                    Files.move(entry, target, ATOMIC_MOVE)
                }
            }
            removeTree(previous)
            val tempReceipt = parent.resolve("sugar-image.sha256.tmp")
            tempReceipt.toFile().writeText(bundle.getString("sha256"))
            Files.move(tempReceipt, receipt, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            removeTree(stage)
        }
    }

    private fun unpack(context: Context, bundle: JSONObject, stage: Path, onProgress: (String) -> Unit) {
        val count = bundle.getInt("entries")
        val size = bundle.getLong("unpackedBytes")
        require(count in 1..200_000 && size in 1..4_294_967_296L)
        val expectedExecutables = bundle.getJSONArray("executables")
        val executables = (0 until expectedExecutables.length()).map { expectedExecutables.getString(it) }.toMutableSet()
        val hash = MessageDigest.getInstance("SHA-256")
        var files = 0
        var bytes = 0L
        var lastPercent = -1
        val buffer = ByteArray(128 * 1024)
        context.assets.open("guest/rootfs.zip").use { input ->
            val verified = DigestInputStream(input.buffered(buffer.size), hash)
            ZipInputStream(verified).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val parts = name.split('/')
                    require(!entry.isDirectory && parts.size > 1 && parts.first() in systemRoots &&
                        parts.none { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) {
                        "系统镜像包含无效路径"
                    }
                    check(++files <= count) { "系统镜像文件数与清单不符" }
                    val out = stage.resolve(name)
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out, CREATE_NEW, WRITE).use { file ->
                        while (true) {
                            val length = zip.read(buffer)
                            if (length < 0) break
                            bytes += length
                            check(bytes <= size) { "系统镜像超过清单大小" }
                            file.write(buffer, 0, length)
                            val percent = (100 * bytes / size).toInt()
                            if (percent != lastPercent) {
                                onProgress(context.sugarString(R.string.splash_prepare_linux, percent))
                                lastPercent = percent
                            }
                        }
                    }
                    if (executables.remove(name)) check(out.toFile().setExecutable(true, true)) { "无法设置程序执行权限" }
                }
                // ZipInputStream stops at the central directory. Hash the remainder of the signed asset too.
                while (verified.read(buffer) != -1) { /* consume */ }
            }
        }
        val observed = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(observed == bundle.getString("sha256") && files == count && bytes == size && executables.isEmpty()) {
            "系统镜像完整性校验失败"
        }
    }

    internal fun removeTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

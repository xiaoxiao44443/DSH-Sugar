package app.dsh.sugar.runtime

import android.content.Context
import com.sucrune.probe.RuntimeClient
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Adds the APK's networking tools to both existing and new Linux environments. */
internal object NetworkTools {
    private data class Bundle(val files: JSONArray, val version: String)
    private var cached: Bundle? = null
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Synchronized fun install(app: Context, client: RuntimeClient, env: String, metadata: Path, baseImage: String = "") {
        val bundle = cached ?: app.assets.open("network-tools/manifest.json").use {
            val data = it.readBytes()
            Bundle(JSONArray(String(data, Charsets.UTF_8)), sha256(data)).also { result -> cached = result }
        }
        val version = bundle.version + baseImage
        val receipt = metadata.resolve("sugar-network-tools.json")
        val previous = if (Files.isRegularFile(receipt, NOFOLLOW_LINKS)) JSONObject(receipt.toFile().readText()) else JSONObject()
        if (previous.optString("version") == version) return
        check(client.getEnvironment(env).getString("state") != "running") { "请在沙箱停止后更新网络工具。" }
        val installed = JSONObject()
        val old = previous.optJSONObject("installed")
        val root = metadata.resolve("workspace")
        app.assets.open("network-tools/tools.zip").use { asset ->
            ZipInputStream(asset.buffered()).use { zip ->
                for (i in 0 until bundle.files.length()) {
                    val file = bundle.files.getJSONObject(i)
                    val path = file.getString("path")
                    val entry = zip.nextEntry ?: error("网络工具包不完整")
                    require(!entry.isDirectory && path == "/" + entry.name &&
                        path.startsWith("/") && entry.name.split('/').none { it.isEmpty() || it == "." || it == ".." || '\\' in it })
                    if (baseImage.isNotEmpty() && file.optBoolean("projectOnly")) continue
                    val hash = file.getString("sha256")
                    val directory = path.substringBeforeLast('/')
                    client.makeDirectory(env, directory)
                    ProjectTreeCopy.openSource(root, directory).use file@{ dir ->
                        val target = Paths.get("/proc/self/fd/${dir.fd}").resolve(path.substringAfterLast('/'))
                        if (Files.exists(target, NOFOLLOW_LINKS)) {
                            if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) return@file
                            if (old?.has(path) != true && Files.size(target) != file.getLong("bytes")) return@file
                            // Preserve files installed or edited independently in this project.
                            val current = Files.newInputStream(target, NOFOLLOW_LINKS).use { input ->
                                val digest = MessageDigest.getInstance("SHA-256")
                                val block = ByteArray(32 * 1024)
                                while (true) {
                                    val count = input.read(block)
                                    if (count < 0) break
                                    digest.update(block, 0, count)
                                }
                                digest.digest().joinToString("") { "%02x".format(it) }
                            }
                            if (current == hash) { installed.put(path, hash); return@file }
                            if (current != old?.optString(path)) return@file
                        }
                        client.importFile(env, path, zip, file.getLong("bytes"), hash, file.getBoolean("executable"))
                        installed.put(path, hash)
                    }
                }
                check(zip.nextEntry == null) { "网络工具包包含未知文件" }
            }
        }
        val pending = metadata.resolve("sugar-network-tools.tmp")
        Files.write(pending, JSONObject().put("version", version).put("installed", installed).toString().toByteArray())
        Files.move(pending, receipt, ATOMIC_MOVE, REPLACE_EXISTING)
    }
}

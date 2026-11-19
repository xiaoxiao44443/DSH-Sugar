package app.dsh.sugar.runtime

import android.content.Context
import com.sucrune.probe.RuntimeClient
import com.sucrune.probe.RuntimeExceptionWithCode
import com.sucrune.probe.TrustedExecutable
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** The image builder writes Linux layers and BusyBox applets first, then the DSH-only opt tree. */
internal object ProjectUserland {
    const val LEGACY_BASE = "sugar-project-tools-v1"
    private const val LAYOUT = "sugar-project-layout-v2"
    private const val ADB = "/usr/bin/adb"
    val systemRoots = setOf("bin", "etc", "lib", "lib64", "sbin", "share", "usr", "var")
    private data class Helpers(val digest: String, val files: Map<String, ByteArray>)
    private var helpers: Helpers? = null
    private data class Launcher(val file: TrustedExecutable, val bytes: ByteArray)
    private var launcher: Launcher? = null

    /** The control environment probes the same static ELF that project subprocesses execute. */
    fun sandboxLauncher(app: Context): TrustedExecutable = launcherAsset(app).file

    @Synchronized private fun launcherAsset(app: Context): Launcher {
        launcher?.let { return it }
        val path = app.assets.open("project/sandbox/path").bufferedReader().use { it.readText() }
        val bytes = app.assets.open("project/sandbox/landlock-run").use { it.readBytes() }
        return Launcher(TrustedExecutable(path, bytes), bytes).also { launcher = it }
    }

    data class Pnpm(val root: String, val files: JSONObject, val wrapper: ByteArray, val legacy: ByteArray)
    private var cachedPnpm: Pnpm? = null

    @Synchronized fun pnpm(app: Context): Pnpm = cachedPnpm ?: run {
        val manifest = app.assets.open("pnpm/manifest.json").use { it.readBytes() }
        val config = JSONObject(String(manifest, Charsets.UTF_8))
        val release = config.getString("version")
        require(release.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        val hash = MessageDigest.getInstance("SHA-256").digest(manifest).joinToString("") { "%02x".format(it) }
        val root = "/opt/sugar/pnpm/$release-${hash.take(12)}"
        val entry = "require('$root/bin/pnpm.cjs')\n"
        val legacy = "#!/usr/bin/node\n$entry".toByteArray()
        val wrapper = ("#!/usr/bin/node\n" +
            "process.env.npm_config_update_notifier ??= process.env.NPM_CONFIG_UPDATE_NOTIFIER ?? 'false';\n" + entry).toByteArray()
        val files = config.getJSONObject("files")
        for (name in files.keys()) {
            require(name.split('/').all { it.matches(Regex("[a-zA-Z0-9_.-]+")) && it != "." && it != ".." })
        }
        Pnpm(root, files, wrapper, legacy).also { cachedPnpm = it }
    }

    /** The shared DSH host also needs pnpm in its separate working image. */
    @Synchronized fun preparePackageManager(app: Context, client: RuntimeClient, env: String) {
        val (root, assets, wrapper, legacy) = pnpm(app)
        var havePayload = false
        try {
            val installed = client.readFile(env, "/usr/bin/pnpm")
            if (installed.contentEquals(wrapper)) return
            havePayload = installed.contentEquals(legacy)
        } catch (e: RuntimeExceptionWithCode) { if (e.code != "not_found") throw e }
        if (!havePayload) {
            for (name in assets.keys()) {
                val target = "$root/$name"
                val metadata = assets.getJSONObject(name)
                client.makeDirectory(env, target.substringBeforeLast('/'))
                app.assets.open("pnpm/files/$name").use { input ->
                    client.importFile(env, target, input, metadata.getLong("size"), metadata.getString("sha256"))
                }
            }
        }
        val wrapperHash = MessageDigest.getInstance("SHA-256").digest(wrapper).joinToString("") { "%02x".format(it) }
        client.importFile(env, "/usr/bin/pnpm", wrapper.inputStream(), wrapper.size.toLong(), wrapperHash, executable = true)
    }

    /** Bootstrapped helpers are app-owned. An app update can refresh these without replacing user-installed tools. */
    fun prepareHelpers(app: Context, client: RuntimeClient, env: String, metadata: Path) {
        SharedProjectTools.install(app, client, env, metadata)
        val bundle = helpers ?: loadHelpers(app).also { helpers = it }
        val receipt = metadata.resolve("sugar-project-helpers.sha256")
        if (Files.isRegularFile(receipt, NOFOLLOW_LINKS) && receipt.toFile().readText() == bundle.digest) return
        check(client.getEnvironment(env).getString("state") != "running") { "项目仍在运行，请在它停止后更新项目工具。" }
        val runner = sandboxLauncher(app)
        for ((guest, bytes) in bundle.files) {
            client.makeDirectory(env, guest.substringBeforeLast('/'))
            if (guest == runner.guestPath || guest == ADB || guest == "/usr/bin/sugar-root" || guest == "/usr/bin/mobile_use") {
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                client.importFile(env, guest, bytes.inputStream(), bytes.size.toLong(), hash, executable = true)
            } else client.writeFile(env, guest, bytes)
        }
        val tmp = metadata.resolve("sugar-project-helpers.tmp")
        Files.write(tmp, bundle.digest.toByteArray())
        Files.move(tmp, receipt, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun loadHelpers(app: Context): Helpers {
        val files = sortedMapOf<String, ByteArray>()
        fun visit(asset: String, guest: String) {
            val entries = app.assets.list(asset).orEmpty()
            if (entries.isEmpty()) files[guest] = app.assets.open(asset).use {
                GuestExecutableScript.normalize(it.readBytes())
            }
            else entries.forEach { visit("$asset/$it", "$guest/$it") }
        }
        visit("project/node-pty", "/opt/sugar/node_modules/node-pty")
        visit("project/process-packages", "/opt/sugar/node_modules/@deepseek-ai")
        visit("project/creator", "/opt/dsh/node_modules/@deepseek-ai")
        val runner = launcherAsset(app)
        files[runner.file.guestPath] = runner.bytes
        visit("guest/sugar-adb.cjs", ADB)
        visit("guest/sugar-root.cjs", "/usr/bin/sugar-root")
        visit("guest/mobile_use", "/usr/bin/mobile_use")
        visit("guest/mobile_use.py", "/opt/sugar/mobile-use/mobile_use.py")
        for (name in listOf("project-fs.mjs", "project-worker.cjs", "project-process-worker.cjs", "plugin-files-worker.cjs")) {
            visit("guest/$name", "/opt/sugar/$name")
        }
        val hash = MessageDigest.getInstance("SHA-256")
        files.forEach { (path, bytes) ->
            hash.update(path.toByteArray()); hash.update(0)
            hash.update(MessageDigest.getInstance("SHA-256").digest(bytes))
        }
        return Helpers(hash.digest().joinToString("") { "%02x".format(it) }, files)
    }

    /** Per-project writable paths; signed system binaries are installed by SharedProjectTools. */
    fun prepare(root: Path, metadata: Path) {
        val layout = metadata.resolve(LAYOUT)
        if (Files.isRegularFile(layout, NOFOLLOW_LINKS) || Files.isRegularFile(metadata.resolve(LEGACY_BASE), NOFOLLOW_LINKS)) return
        listOf("etc", "workspace", "tmp", "home/sugar").forEach { Files.createDirectories(root.resolve(it)) }
        Files.write(root.resolve("etc/resolv.conf"), "nameserver 127.0.0.53\noptions timeout:2 attempts:2\n".toByteArray())
        Files.write(root.resolve("etc/nsswitch.conf"), "hosts: files dns\n".toByteArray())
        Files.write(root.resolve("etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n".toByteArray())
        Files.createFile(layout)
    }
}

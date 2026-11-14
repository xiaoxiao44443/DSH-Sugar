package app.dsh.sugar.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.sucrune.probe.RuntimeClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Only signed APK bytes enter this store. A guest sees regular files through the Sucrune broker. */
internal object SharedProjectTools {
    private const val PREFIX = "sucrune:base:v1:"
    private const val PY = "/opt/sugar/mobile-python"
    private const val RECEIPT = "sugar-shared-tools-v1"
    private const val FILE_MODE = 384 // 0600, like RuntimeClient.importFile
    private const val EXEC_MODE = 448 // 0700
    private val keyPattern = Regex("0[0-7]{3}-[a-f0-9]{64}")
    private val versionPattern = Regex("[a-f0-9]{64}")
    private data class Package(val version: String, val python: String, val network: JSONArray, val executables: Set<String>, val pnpm: ProjectUserland.Pnpm)
    private var cachedPackage: Package? = null
    private var cachedIndex: Map<String,String>? = null
    private fun sha() = MessageDigest.getInstance("SHA-256")
    private val hex = "0123456789abcdef".toCharArray()
    private fun ByteArray.hex(): String = buildString(size * 2) {
        for (b in this@hex) { append(hex[(b.toInt() and 255) ushr 4]); append(hex[b.toInt() and 15]) }
    }
    private fun pack(context: Context): Package = cachedPackage ?: run {
        val python = context.assets.open("mobile-python/runtime.sha256").bufferedReader().use { it.readText().trim() }
        require(versionPattern.matches(python))
        val net = context.assets.open("network-tools/manifest.json").use { it.readBytes() }
        val guest = context.assets.open("guest/bundle.json").bufferedReader().use { JSONObject(it.readText()) }
        val guestHash = guest.getString("sha256")
        require(versionPattern.matches(guestHash))
        val paths = guest.getJSONArray("executables")
        val executable = (0 until paths.length()).map(paths::getString).toSet()
        val pnpm = ProjectUserland.pnpm(context)
        val version = sha().apply {
            update("sugar-tools-v1.2:$python:$guestHash:${pnpm.root}:".toByteArray())
            update(pnpm.wrapper)
            update(net)
        }.digest().hex()
        Package(version, python, JSONArray(String(net, Charsets.UTF_8)), executable, pnpm).also { cachedPackage = it }
    }

    @Synchronized fun install(context: Context, client: RuntimeClient, env: String, metadata: Path) {
        val bundle = pack(context)
        val receipt = metadata.resolve(RECEIPT)
        val previousVersion = if (Files.isRegularFile(receipt, NOFOLLOW_LINKS)) receipt.toFile().readText() else null
        if (previousVersion == bundle.version) return
        // Existing long-running servers keep their current tools until the next idle admission.
        if (client.getEnvironment(env).getString("state") == "running") return
        val store = metadata.parent.parent.resolve("shared-tools-v1")
        val manifests = store.resolveSibling("shared-tool-manifests-v1")
        Files.createDirectories(store)
        Os.chmod(store.toString(), EXEC_MODE)
        Files.createDirectories(manifests)
        syncDirectory(store.parent)
        val current = cachedIndex ?: prepareIndex(context, store, manifests, bundle).also { cachedIndex = it }
        val previous = previousVersion?.let {
            require(versionPattern.matches(it))
            readIndex(manifests.resolve(it))
        }.orEmpty().toMutableMap()
        // Older releases kept only category receipts. Their frozen inventories also distinguish a
        // deliberate deletion from a path introduced by a later APK, including partial migrations.
        val missing = buildList {
            fun has(name: String) = Files.isRegularFile(metadata.resolve(name), NOFOLLOW_LINKS)
            if ("/usr/bin/node" !in previous && has(ProjectUserland.LEGACY_BASE)) add("system")
            if ("$PY/bin/python3.11" !in previous && has("sugar-mobile-python.sha256")) add("python")
            if ("/usr/bin/pnpm" !in previous && has("sugar-project-helpers.sha256")) add("pnpm")
        }
        if (missing.isNotEmpty()) {
            val baseline = context.assets.open("guest/legacy-project-tools-v1.json").bufferedReader().use { JSONObject(it.readText()) }
            for (category in missing) {
                val entries = baseline.getJSONObject(category)
                for (path in entries.keys()) previous.putIfAbsent(path, entries.getString(path))
            }
        }
        val oldNet = metadata.resolve("sugar-network-tools.json").takeIf {
            "/usr/bin/curl" !in previous && Files.isRegularFile(it, NOFOLLOW_LINKS)
        }?.let { JSONObject(it.toFile().readText()).optJSONObject("installed") }
        publishProject(metadata, current, previous, oldNet)
        writeAtomic(receipt, bundle.version.toByteArray())
    }

    /** A pinned guest symlink may name stock bytes. Never expose an object descriptor opened for writing. */
    fun openReference(store: Path, target: String): ParcelFileDescriptor? {
        if (!target.startsWith(PREFIX)) return null
        val key = target.removePrefix(PREFIX)
        require(keyPattern.matches(key))
        return ProjectTreeCopy.directory(store.toString()).use { directory ->
            val owner = android.os.Process.myUid()
            val parent = Os.fstat(directory.fileDescriptor)
            check(parent.st_uid == owner && (parent.st_mode and 4095) == EXEC_MODE)
            val fd = Os.open("/proc/self/fd/${directory.fd}/$key",
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
            try {
                val stat = Os.fstat(fd)
                check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == owner && stat.st_nlink == 1L &&
                    (stat.st_mode and 4095) == key.substringBefore('-').toInt(8))
                ParcelFileDescriptor.dup(fd)
            } finally { Os.close(fd) }
        }
    }

    private fun readIndex(file: Path): Map<String,String> {
        val json = JSONObject(file.toFile().readText())
        val files = json.optJSONObject("files") ?: json
        return files.keys().asSequence().associateWith { path ->
            requireGuestPath(path)
            files.getString(path).also { require(keyPattern.matches(it)) }
        }
    }

    private fun prepareIndex(context: Context, store: Path, manifests: Path, bundle: Package): Map<String,String> {
        // This single-writer installer runs only in the app process. A power loss can leave
        // a partial archive entry (notably the Node executable); guest references never name it.
        for (directory in listOf(store, manifests)) {
            Files.newDirectoryStream(directory, ".pending-*").use { entries ->
                for (entry in entries) if (Files.isRegularFile(entry, NOFOLLOW_LINKS)) Files.deleteIfExists(entry)
            }
        }
        val manifest = manifests.resolve(bundle.version)
        if (Files.isRegularFile(manifest, NOFOLLOW_LINKS)) return readIndex(manifest)
        val rows = linkedMapOf<String,String>()
        fun add(path: String, input: InputStream, executable: Boolean, expected: String? = null, bytes: Long? = null) {
            requireGuestPath(path)
            val key = publishObject(store, input, executable, expected, bytes)
            val existing = rows.put(path, key)
            check(existing == null || existing == key) { "Conflicting signed tool path: $path" }
        }
        context.assets.open("guest/rootfs.zip").use { raw ->
            ZipInputStream(raw.buffered(128 * 1024)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val root = entry.name.substringBefore('/')
                    // The project Linux layers precede the host-only /opt archive.
                    if (root == "opt") break
                    require(!entry.isDirectory && root in ProjectUserland.systemRoots)
                    add("/${entry.name}", zip, entry.name in bundle.executables)
                }
            }
        }
        context.assets.open("project/tools/rg").use { add("/usr/bin/rg", it, true) }
        val system = rows.keys.toSet()
        check(listOf("/bin/bash", "/bin/sh", "/bin/busybox", "/usr/bin/node").all { it in system })
        val pnpm = bundle.pnpm
        for (name in pnpm.files.keys()) {
            val entry = pnpm.files.getJSONObject(name)
            context.assets.open("pnpm/files/$name").use {
                add("${pnpm.root}/$name", it, false, entry.getString("sha256"), entry.getLong("size"))
            }
        }
        add("/usr/bin/pnpm", pnpm.wrapper.inputStream(), true)
        context.assets.open("network-tools/tools.zip").use { source ->
            ZipInputStream(source.buffered()).use { zip ->
                for (i in 0 until bundle.network.length()) {
                    val file = bundle.network.getJSONObject(i)
                    val path = file.getString("path")
                    val entry = checkNotNull(zip.nextEntry)
                    require(!entry.isDirectory && path == "/" + entry.name)
                    add(path, zip, file.getBoolean("executable"), file.getString("sha256"), file.getLong("bytes"))
                }
                check(zip.nextEntry == null)
            }
        }
        ProjectMobilePython.unpack(context, bundle.python) { path, input, executable -> add(path, input, executable) }
        syncDirectory(store)
        writeAtomic(manifest, JSONObject(rows).toString().toByteArray())
        return rows
    }

    private fun requireGuestPath(path: String) {
        require(path.startsWith("/") && path.drop(1).split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && '\u0000' !in it
        })
    }

    private fun publishObject(store: Path, source: InputStream, executable: Boolean, expected: String?, bytes: Long?): String {
        val mode = if (executable) EXEC_MODE else FILE_MODE
        val head = if (executable) "0700-" else "0600-"
        if (expected != null) {
            require(versionPattern.matches(expected))
            val blob = store.resolve(head + expected)
            if (Files.isRegularFile(blob, NOFOLLOW_LINKS) && Files.size(blob) == bytes) return head + expected
        }
        val staged = Files.createTempFile(store, ".pending-", "")
        try {
            val digest = sha()
            // Do not close source; it can be the current entry in a signed ZipInputStream.
            val count = FileOutputStream(staged.toFile()).use { output ->
                val size = DigestInputStream(source, digest).copyTo(output)
                Os.fchmod(output.fd, mode)
                output.fd.sync()
                size
            }
            val hash = digest.digest().hex()
            check((expected == null || hash == expected) && (bytes == null || bytes == count))
            val key = head + hash
            val blob = store.resolve(key)
            if (!Files.exists(blob, NOFOLLOW_LINKS)) Files.move(staged, blob, ATOMIC_MOVE)
            return key
        } finally { Files.deleteIfExists(staged) }
    }

    private fun writeAtomic(file: Path, content: ByteArray) {
        val temp = Files.createTempFile(file.parent, ".pending-", "")
        try {
            FileOutputStream(temp.toFile()).use { it.write(content); it.fd.sync() }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(file.parent)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun syncDirectory(path: Path) {
        ProjectTreeCopy.directory(path.toString()).use { Os.fsync(it.fileDescriptor) }
    }

    private fun publishProject(
        metadata: Path, current: Map<String,String>, old: Map<String,String>, legacyNet: JSONObject?
    ) {
        val stage = metadata.resolve("sugar-shared-entry.pending")
        Files.deleteIfExists(stage)
        val root = metadata.resolve("workspace")
        fun existed(path: String) = path in old || legacyNet?.has(path) == true
        val paths = current.keys + old.keys
        val oldDirs = mutableSetOf<String>()
        for (path in paths.filter(::existed)) {
            var parent = path.substringBeforeLast('/')
            while (parent.isNotEmpty() && oldDirs.add(parent)) parent = parent.substringBeforeLast('/')
        }
        try {
            for ((directory, entries) in paths.groupBy { it.substringBeforeLast('/') }) {
                openDirectory(root, directory, oldDirs)?.use { dir ->
                    val location = Paths.get("/proc/self/fd/${dir.fd}")
                    var changed = false
                    for (path in entries) {
                        val file = location.resolve(path.substringAfterLast('/'))
                        val next = current[path]
                        val previous = old[path] ?: legacyNet?.optString(path)?.takeIf { versionPattern.matches(it) }
                            ?.let { next?.substringBefore('-')?.plus("-")?.plus(it) }
                        val owned = when {
                            Files.isSymbolicLink(file) -> {
                                val marker = Files.readSymbolicLink(file).toString()
                                if (next != null && marker == PREFIX + next) continue
                                previous != null && marker == PREFIX + previous
                            }
                            Files.isRegularFile(file, NOFOLLOW_LINKS) -> {
                                val stat = Os.lstat(file.toString())
                                if (stat.st_nlink != 1L) continue
                                val mode = (stat.st_mode and 4095).toString(8).padStart(4, '0')
                                if (listOfNotNull(next, previous).none { it.startsWith("$mode-") }) continue
                                val content = Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                                    val digest = sha()
                                    val buf = ByteArray(32 * 1024)
                                    while (true) {
                                        val count = input.read(buf)
                                        if (count < 0) break
                                        digest.update(buf, 0, count)
                                    }
                                    "$mode-${digest.digest().hex()}"
                                }
                                content == next || content == previous
                            }
                            Files.notExists(file, NOFOLLOW_LINKS) -> !existed(path)
                            else -> false
                        }
                        if (!owned) continue
                        if (next == null) Files.deleteIfExists(file) else {
                            Files.createSymbolicLink(stage, Paths.get(PREFIX + next))
                            Files.move(stage, file, ATOMIC_MOVE, REPLACE_EXISTING)
                        }
                        changed = true
                    }
                    if (changed) Os.fsync(dir.fileDescriptor)
                }
            }
        } finally { Files.deleteIfExists(stage) }
    }

    /** A user may have removed a directory or replaced it with a symlink. Neither case follows that link. */
    private fun openDirectory(root: Path, path: String, oldDirs: Set<String>): ParcelFileDescriptor? {
        var parent = ProjectTreeCopy.directory(root.toString())
        var guest = ""
        try {
            for (part in path.drop(1).split('/')) {
                guest += "/$part"
                val child = "/proc/self/fd/${parent.fd}/$part"
                val next = try {
                    ProjectTreeCopy.directory(child)
                } catch (_: IllegalArgumentException) {
                    parent.close()
                    return null
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.ELOOP || e.errno == OsConstants.ENOTDIR ||
                        (e.errno == OsConstants.ENOENT && guest in oldDirs)) { parent.close(); return null }
                    if (e.errno != OsConstants.ENOENT) throw e
                    Os.mkdir(child, EXEC_MODE)
                    Os.fsync(parent.fileDescriptor)
                    ProjectTreeCopy.directory(child)
                }
                parent.close()
                parent = next
            }
            return parent
        } catch (failure: Throwable) { parent.close(); throw failure }
    }
}

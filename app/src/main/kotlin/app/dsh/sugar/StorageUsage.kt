package app.dsh.sugar

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.JsonReader
import app.dsh.sugar.runtime.ProjectSandboxes
import app.dsh.sugar.runtime.ProjectTreeCopy
import app.dsh.sugar.runtime.isWorkspaceRoot
import app.dsh.sugar.workspace.LocalFolderWorkspaces
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.Executors

/** Browsing storage never initializes a runtime. A scan is cancelled when its page closes. */
internal class StorageUsage(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var active: CancellationSignal? = null

    fun cancel() {
        active?.cancel()
        active = null
    }

    fun query(request: JSONObject, reply: (JSONObject) -> Unit) {
        val id = request.optString("project")
        val token = request.optInt("token")
        cancel()
        val signal = CancellationSignal().also { active = it }
        worker.execute {
            val result = JSONObject().put("type", "storageUsage").put("token", token).put("project", id)
            try {
                signal.throwIfCanceled()
                val registry = ProjectSandboxes.storageRegistry(context)
                if (id.isEmpty()) result.put("usage", snapshot(registry, signal))
                else {
                    val root = registry.getJSONObject("items").getJSONObject(id).getString("root")
                    val folder = checkNotNull(LocalFolderWorkspaces.verifiedRoot(context, id, root, signal))
                    val tree = folder.tree
                    var modifiedAt = folder.stat.modifiedMillis()
                    val pending = ArrayDeque<String>()
                    val seen = mutableSetOf<String>()
                    pending.addLast(DocumentsContract.getTreeDocumentId(tree))
                    var bytes = 0L
                    var partial = false
                    while (pending.isNotEmpty()) {
                        signal.throwIfCanceled()
                        val parent = pending.removeLast()
                        if (!seen.add(parent)) continue
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                        val cursor = context.contentResolver.query(children, columns, null, null, null, signal)
                        if (cursor == null) { partial = true; continue }
                        cursor.use {
                            while (it.moveToNext()) {
                                signal.throwIfCanceled()
                                if (!it.isNull(3)) modifiedAt = maxOf(modifiedAt, it.getLong(3))
                                if (it.getString(1) == Document.MIME_TYPE_DIR) pending.addLast(it.getString(0))
                                else if (it.isNull(2) || it.getLong(2) < 0L) partial = true
                                else bytes += it.getLong(2)
                            }
                            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) partial = true
                        }
                    }
                    result.put("external", Amount(bytes, partial).json())
                    if (modifiedAt > 0L) result.put("modifiedAt", modifiedAt)
                }
            } catch (_: Exception) {
                result.put("failed", true)
            }
            main.post {
                if (active === signal && !signal.isCanceled) {
                    active = null
                    reply(result)
                }
            }
        }
    }

    private data class Amount(var bytes: Long = 0, var partial: Boolean = false) {
        fun json() = JSONObject().put("bytes", bytes).put("partial", partial)
    }

    private fun StructStat.modifiedMillis() = st_mtim.tv_sec * 1000L + st_mtim.tv_nsec / 1_000_000L

    /** Match registered content roots during the byte scan, including unmigrated control-environment projects. */
    private class WorkspacePath {
        val children = mutableMapOf<String, WorkspacePath>()
        var project: JSONObject? = null

        fun register(path: String, row: JSONObject) {
            var node = this
            for (name in path.split('/')) node = node.children.getOrPut(name) { WorkspacePath() }
            node.project = row
        }
    }

    private fun snapshot(registry: JSONObject, signal: CancellationSignal): JSONObject {
        val runtime = File(context.filesDir, "sucrune")
        val items = registry.getJSONObject("items")
        val projects = JSONArray()
        val byEnv = mutableMapOf<String, Amount>()
        val shared = Amount()
        val control = Amount()
        val other = Amount()
        val rows = mutableListOf<Pair<JSONObject, Amount?>>()
        val paths = WorkspacePath()
        val phoneGrants by lazy { LocalFolderWorkspaces.writableTrees(context) }
        var controlEnv = registry.optString("control")
        for (id in items.keys()) {
            signal.throwIfCanceled()
            val row = items.getJSONObject(id)
            val env = row.optString("envId")
            val amount = if (envId.matches(env)) byEnv.getOrPut(env) { Amount() } else null
            val root = row.getString("root")
            val ready = row.remove("ready") == true
            when {
                row.optBoolean("external") -> {
                    // Reading the verified directory's own mtime does not enumerate phone files.
                    val stamp = try { LocalFolderWorkspaces.verifiedRoot(context, id, root, signal, phoneGrants)?.stat?.modifiedMillis() }
                    catch (_: Exception) { null }
                    if (stamp != null && stamp > 0L) row.put("modifiedAt", stamp)
                }
                ready && amount != null -> paths.register("environments/$env/workspace/workspace", row)
                envId.matches(controlEnv) && isWorkspaceRoot(root) ->
                    paths.register("environments/$controlEnv/workspace$root", row)
            }
            rows.add(row.put("id", id) to amount)
        }
        val seen = mutableSetOf<Pair<Long, Long>>()
        val found = mutableSetOf<String>()
        val header = ByteArray(512)
        fun walk(file: File, depth: Int, group: String?, amount: Amount, path: WorkspacePath?, project: JSONObject?) {
            signal.throwIfCanceled()
            try {
                val info = Os.lstat(file.path)
                val directory = OsConstants.S_ISDIR(info.st_mode)
                val contentOwner = if (directory) path?.project ?: project else project
                if (contentOwner != null) {
                    val modifiedAt = info.modifiedMillis()
                    if (modifiedAt > contentOwner.optLong("modifiedAt")) contentOwner.put("modifiedAt", modifiedAt)
                }
                if (!directory && info.st_nlink > 1 && !seen.add(info.st_dev to info.st_ino)) return
                amount.bytes += info.st_blocks * 512L
                if (!directory) return
                if (depth >= 192) { amount.partial = true; return }
                // Guest paths can change during a scan. Pin each directory and never follow a symlink.
                ProjectTreeCopy.directory(file.path).use { handle ->
                    val pinned = Os.fstat(handle.fileDescriptor)
                    if (pinned.st_dev != info.st_dev || pinned.st_ino != info.st_ino) { amount.partial = true; return }
                    val dir = File("/proc/self/fd/${handle.fd}")
                    val names = dir.list() ?: throw IOException("readdir")
                    if (depth == 0 && controlEnv.isEmpty()) names.sortBy { it != "environments" }
                    for (name in names) {
                        val child = File(dir, name)
                        val bucket = when {
                            depth == 0 && (name == "shared-tools-v1" || name == "shared-tool-manifests-v1") -> shared
                            group == "environments" && name == controlEnv -> control
                            group == "environments" && name in byEnv -> byEnv.getValue(name).also { found.add(name) }
                            // Before the first workspace is registered, only the catalog knows the control ID.
                            group == "environments" && controlEnv.isEmpty() && envId.matches(name) && isControl(child) ->
                                control.also { controlEnv = name }
                            group == "executions" && executionId.matches(name) -> {
                                val owner = executionOwner(child, header)
                                if (owner != null && owner == controlEnv) control else owner?.let(byEnv::get) ?: other
                            }
                            else -> amount
                        }
                        walk(child, depth + 1, if (depth == 0) name else null, bucket, path?.children?.get(name), contentOwner)
                    }
                }
            } catch (e: ErrnoException) {
                if (depth != 0 || e.errno != OsConstants.ENOENT) amount.partial = true
            } catch (_: IOException) {
                amount.partial = true
            } catch (_: IllegalArgumentException) {
                amount.partial = true
            }
        }
        walk(runtime, 0, null, other, paths, null)
        for ((row, amount) in rows) {
            if (amount != null) {
                if (row.getString("envId") !in found) amount.partial = true
                row.put("usage", amount.json())
            }
            row.remove("envId")
            projects.put(row)
        }
        return JSONObject().put("shared", shared.json()).put("control", control.json())
            .put("other", other.json()).put("rows", projects)
    }

    private fun isControl(env: File): Boolean = try {
        // The catalog and its small metadata files are outside guest-writable storage.
        JSONObject(File(env, "environment.json").readText()).optString("name") == "DSH Sugar"
    } catch (_: Exception) { false }

    private fun executionOwner(directory: File, header: ByteArray): String? {
        // Sucrune stores env_id among the first three fields. Never load logs or command payloads.
        return try {
            val length = File(directory, "initial.json").inputStream().use { it.read(header) }
            if (length <= 0) return null
            JsonReader(StringReader(String(header, 0, length, Charsets.UTF_8))).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "env_id") return reader.nextString()
                    reader.skipValue()
                }
                null
            }
        } catch (_: Exception) { null }
    }

    companion object {
        private val envId = Regex("env_[0-9a-f]{32}")
        private val executionId = Regex("exec_[0-9a-f]{32}")
        private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED)
        private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SugarStorage").apply { isDaemon = true } }
    }
}

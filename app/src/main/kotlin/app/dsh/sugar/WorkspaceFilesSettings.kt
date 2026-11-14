package app.dsh.sugar

import android.app.Activity
import android.app.AlertDialog
import android.util.Base64
import app.dsh.sugar.workspace.WorkspaceFileStore
import org.json.JSONObject
import java.util.concurrent.Executors

internal class WorkspaceFilesSettings(private val activity: Activity) {
    private val store = WorkspaceFileStore(activity.applicationContext)
    private var project = ""
    private var directory = ""
    private var generation = 0L
    private var busy = false
    private var entries = emptyMap<String, JSONObject>()
    private var projectName = ""
    private var phoneFolder = false
    private var partial = false
    private var dialog: AlertDialog? = null

    fun page(id: String, path: String) {
        if (id == project && path == directory) return
        generation++
        project = id
        directory = path
        entries = emptyMap()
        projectName = ""
        phoneFolder = false
        partial = false
        busy = false
        dialog?.dismiss()
        dialog = null
    }

    fun handle(data: JSONObject, focused: Boolean, reply: (JSONObject) -> Unit) {
        val id = data.optString("id")
        val path = data.optString("path")
        val token = data.optLong("token", -1)
        val type = when (data.optString("type")) {
            "workspaceFilesOpen" -> "workspaceFilesOpened"
            "workspaceFilesDelete" -> "workspaceFilesResult"
            "workspaceFilesQuery" -> "workspaceFiles"
            else -> return
        }
        fun response(success: Boolean) = JSONObject().put("type", type).put("token", token)
            .put("id", id).put("path", path).put("success", success)
        fun failure(error: Int = R.string.ws_file_unavailable) =
            reply(response(false).put("error", activity.sugarString(error)))
        if (id.isEmpty() || id != project || path != directory || token < 0 || busy ||
            activity.isDestroyed || activity.isFinishing) { failure(); return }
        val stamp = generation
        fun current() = stamp == generation && !activity.isDestroyed && !activity.isFinishing
        if (type == "workspaceFiles") {
            busy = true
            entries = emptyMap()
            worker.execute {
                val listing = runCatching { store.list(id, path) }.getOrNull()
                activity.runOnUiThread {
                    if (!current()) return@runOnUiThread
                    busy = false
                    val list = listing?.optJSONArray("rows")
                    if (listing == null || list == null) { failure(); return@runOnUiThread }
                    projectName = listing.optString("projectName").ifBlank { id }
                    phoneFolder = listing.optBoolean("phoneFolder")
                    partial = listing.optBoolean("partial")
                    entries = buildMap {
                        for (index in 0 until list.length()) {
                            val row = list.optJSONObject(index) ?: continue
                            put(row.getString("path"), row)
                        }
                    }
                    reply(response(true).put("rows", list).put("title", projectName)
                        .put("partial", listing.optBoolean("partial")))
                }
            }
            return
        }
        if (!focused) { failure(); return }
        if (type == "workspaceFilesOpened") {
            val row = entries[data.optString("entry")]
            if (row == null || row.optString("kind") != "file") { failure(); return }
            busy = true
            worker.execute {
                val prepared = runCatching {
                    StorageFilePreview.workspace(activity, store.openUri(id, row.getString("path")), row)
                }.getOrNull()
                activity.runOnUiThread {
                    if (!current()) { prepared?.discard(); return@runOnUiThread }
                    busy = false
                    if (prepared == null || !activity.hasWindowFocus()) {
                        prepared?.discard()
                        failure(R.string.sugar_file_open_failed)
                    } else runCatching { dialog = prepared.show(activity) }
                        .onFailure { prepared.discard(); failure(R.string.sugar_file_open_failed) }
                        .onSuccess {
                            dialog?.setOnDismissListener { dialog = null }
                            reply(response(true))
                        }
                }
            }
            return
        }
        val clear = data.optBoolean("clear")
        val orderedPaths = entries.keys.toList()
        val paths = if (clear) {
            if (partial) { failure(R.string.ws_changed); return }
            orderedPaths
        } else if (data.optBoolean("all")) {
            orderedPaths.filter { entries.getValue(it).optBoolean("canDelete") }
        } else if (data.has("selectionBits")) {
            val count = orderedPaths.size
            if (count == 0 || data.optInt("rowCount", -1) != count) { failure(R.string.ws_changed); return }
            val encoded = data.optString("selectionBits")
            val length = (count + 7L) / 8L
            if (encoded.length.toLong() != 4L * ((length + 2L) / 3L)) { failure(); return }
            val bits = try { Base64.decode(encoded, Base64.NO_WRAP) }
                catch (_: IllegalArgumentException) { failure(); return }
            if (bits.size.toLong() != length || Base64.encodeToString(bits, Base64.NO_WRAP) != encoded ||
                (count % 8 != 0 && ((bits.last().toInt() and 255) ushr (count % 8)) != 0)) {
                failure(); return
            }
            orderedPaths.filterIndexed { index, _ -> (bits[index / 8].toInt() and (1 shl (index % 8))) != 0 }
        } else {
            val selected = data.optJSONArray("indexes")
            if (selected == null || selected.length() !in 1..orderedPaths.size) { failure(); return }
            buildList {
                for (i in 0 until selected.length()) {
                    val index = selected.opt(i) as? Int
                    if (index == null || index !in orderedPaths.indices) { failure(); return }
                    add(orderedPaths[index])
                }
            }
        }
        if (paths.isEmpty() || paths.distinct().size != paths.size || paths.any { entries[it]?.optBoolean("canDelete") != true }) {
            failure(R.string.ws_changed)
            return
        }
        val snapshot = paths.map { entries.getValue(it) }
        val names = snapshot.take(3).map { it.getString("name") }
        val summary = buildList {
            add("$projectName  ·  /${directory}")
            add(names.joinToString("\n") + if (paths.size > names.size) "\n…" else "")
            if (snapshot.any { it.getString("kind") == "directory" }) add(activity.sugarString(R.string.storage_workspace_delete_contents))
            if (phoneFolder) add(activity.sugarString(R.string.storage_workspace_delete_phone))
        }.joinToString("\n\n")
        busy = true
        fun cancel() {
            if (!current()) return
            busy = false
            reply(response(false).put("cancelled", true))
        }
        dialog = SugarDialog.builder(activity, landscapeMaxWidthDp = 390)
            .setTitle(activity.sugarString(if (clear) R.string.storage_workspace_confirm_clear else R.string.storage_workspace_confirm_files, paths.size))
            .setMessage(summary)
            .setNegativeButton(activity.sugarString(R.string.ws_cancel)) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .setPositiveButton(activity.sugarString(R.string.ws_delete)) { _, _ ->
                if (current()) worker.execute {
                    var removed = 0
                    val success = runCatching {
                        for (entry in snapshot) {
                            store.delete(id, entry.getString("path"), entry.getString("kind"))
                            removed++
                        }
                    }.isSuccess
                    activity.runOnUiThread {
                        if (!current()) return@runOnUiThread
                        busy = false
                        entries = entries - paths.take(removed).toSet()
                        val out = response(success).put("removed", removed)
                        if (!success) out.put("error", activity.sugarString(R.string.ws_action_failed))
                        reply(out)
                    }
                }
            }
            .show().also { alert -> alert.setOnDismissListener { if (dialog === alert) dialog = null } }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "sugar-workspace-files") }
    }
}

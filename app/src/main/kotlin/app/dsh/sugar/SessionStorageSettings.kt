package app.dsh.sugar

import android.app.Activity
import android.app.AlertDialog
import app.dsh.sugar.runtime.SugarRuntime
import org.json.JSONObject
import java.util.concurrent.Executors

internal class SessionStorageSettings(private val activity: Activity) {
    private var epoch = 0L
    private var pending = false
    private var dialog: AlertDialog? = null
    private var listed = emptyMap<String, JSONObject>()
    private var detailId = ""
    private var pageFiles = emptySet<String>()
    private var openEpoch = 0L
    private var opening = false

    fun detailChanged(id: String) {
        if (id == detailId) return
        detailId = id
        pageFiles = emptySet()
        openEpoch++
        opening = false
    }

    fun close() {
        epoch++
        listed = emptyMap()
        detailChanged("")
        pending = false
        dialog?.dismiss()
        dialog = null
    }

    fun handle(data: JSONObject, focused: Boolean, reply: (JSONObject) -> Unit) {
        if (data.optString("type") == "sessionStorageOpen") {
            open(data, focused, reply)
            return
        }
        val deleting = data.optString("type") == "sessionStorageDelete"
        val token = data.optLong("token", -1)
        val id = data.optString("id")
        val detail = !deleting && id.isNotEmpty()
        val mode = data.optString("mode")
        fun response() = JSONObject().put("token", token)
            .putOpt("id", id.takeIf { it.isNotEmpty() })
            .put("type", if (deleting) "sessionStorageResult" else "sessionStorage")
        fun failure() = reply(response().put("success", false))
        if (pending || token < 0) { failure(); return }
        val generation = epoch
        fun execute() {
            openEpoch++
            opening = false
            pageFiles = emptySet()
            pending = true
            val bridge = SugarRuntime.activeProjects
            worker.execute {
                val result = runCatching {
                    val request = when {
                        deleting -> JSONObject().put("op", "delete").put("id", id).put("mode", mode)
                        detail -> JSONObject().put("op", "detail").put("id", id).put("offset", data.optLong("offset", 0))
                        else -> JSONObject().put("op", "list")
                    }
                    checkNotNull(bridge).sessionStorage(request).also { output ->
                        if (detail) StorageFilePreview.decorate(bridge, output.optJSONArray("files"))
                    }
                }
                activity.runOnUiThread {
                    if (epoch != generation || activity.isDestroyed) return@runOnUiThread
                    pending = false
                    val output = result.getOrNull()
                    if (output == null || bridge !== SugarRuntime.activeProjects) {
                        listed = emptyMap()
                        val error = result.exceptionOrNull()?.message?.take(240)
                        reply(response().put("success", false).putOpt("error", error))
                    } else {
                        if (deleting) {
                            listed = emptyMap()
                            output.put("id", id).put("mode", mode)
                        } else if (detail) {
                            val row = output.optJSONObject("row")
                            if (row != null && row.optString("id") == id) listed = listed + (id to row)
                            if (detailId == id) {
                                val files = output.optJSONArray("files")
                                pageFiles = buildSet {
                                    if (files != null) for (i in 0 until files.length())
                                        files.optJSONObject(i)?.optString("fileId")?.takeIf { it.isNotEmpty() }?.let { add(it) }
                                }
                            }
                        } else {
                            val rows = output.optJSONArray("rows")
                            listed = buildMap {
                                if (rows != null) for (i in 0 until rows.length()) {
                                    val row = rows.optJSONObject(i) ?: continue
                                    put(row.optString("id"), row)
                                }
                            }
                        }
                        output.put("token", token).put("type", response().getString("type"))
                        if (!output.has("success")) output.put("success", true)
                        reply(output)
                    }
                }
            }
        }
        if (!deleting) { execute(); return }
        val row = listed[id]
        val message = when (mode) {
            "attachments" -> R.string.session_confirm_attachments
            "conversation" -> R.string.session_confirm_conversation
            "both" -> R.string.session_confirm_both
            else -> { failure(); return }
        }
        if (!focused || activity.isFinishing || activity.isDestroyed || row == null ||
            !row.optBoolean("canDelete") || row.optBoolean("active") ||
            (row.optBoolean("attachmentsOnly") && mode != "attachments")) {
            failure(); return
        }
        pending = true
        fun cancel() {
            if (epoch != generation) return
            pending = false
            reply(response().put("success", false).put("cancelled", true))
        }
        val title = row.optString("title").ifBlank { id }
        dialog = SugarDialog.builder(activity, landscapeMaxWidthDp = 390)
            .setTitle(title)
            .setMessage(activity.sugarString(message))
            .setNegativeButton(activity.sugarString(R.string.ws_cancel)) { _, _ -> cancel() }
            .setPositiveButton(activity.sugarString(R.string.ws_delete)) { _, _ ->
                if (epoch == generation) execute()
            }
            .setOnCancelListener { cancel() }
            .show().also { alert -> alert.setOnDismissListener { if (dialog === alert) dialog = null } }
    }

    private fun open(data: JSONObject, focused: Boolean, reply: (JSONObject) -> Unit) {
        val id = data.optString("id")
        val fileId = data.optString("fileId")
        val token = data.optLong("token", -1)
        fun respond(success: Boolean) {
            val output = JSONObject().put("type", "sessionStorageOpened").put("token", token)
                .put("id", id).put("fileId", fileId).put("success", success)
            if (!success) output.put("error", activity.sugarString(R.string.sugar_file_open_failed))
            reply(output)
        }
        if (!focused || pending || opening || id != detailId || fileId !in pageFiles || token < 0) {
            respond(false)
            return
        }
        val generation = openEpoch
        val bridge = SugarRuntime.activeProjects
        opening = true
        worker.execute {
            val prepared = runCatching {
                val entry = checkNotNull(bridge).sessionStorage(JSONObject().put("op", "resolveAttachment")
                    .put("id", id).put("fileId", fileId))
                check(entry.getString("id") == id && entry.getString("fileId") == fileId)
                StorageFilePreview.session(activity, bridge, entry)
            }.getOrNull()
            activity.runOnUiThread {
                if (generation != openEpoch) { prepared?.discard(); return@runOnUiThread }
                opening = false
                if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus() ||
                    bridge !== SugarRuntime.activeProjects || prepared == null) {
                    prepared?.discard()
                    respond(false)
                } else {
                    runCatching { dialog = prepared.show(activity) }
                        .onFailure { prepared.discard() }
                        .onSuccess { dialog?.setOnDismissListener { dialog = null } }
                        .let { respond(it.isSuccess) }
                }
            }
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "sugar-session-storage") }
    }
}

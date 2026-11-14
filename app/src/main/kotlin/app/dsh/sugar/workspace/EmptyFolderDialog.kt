package app.dsh.sugar.workspace

import app.dsh.sugar.R
import app.dsh.sugar.sugarString

import android.app.Activity
import android.app.AlertDialog
import app.dsh.sugar.SugarDialog
import app.dsh.sugar.runtime.SugarRuntime
import kotlin.concurrent.thread

/** Review the exact directory checked by the Host, and pass that same inspection ticket back on confirmation. */
internal class EmptyFolderDialog(private val activity: Activity) {
    private var pending: Any? = null
    private var dialog: AlertDialog? = null

    fun close() {
        pending = null
        dialog?.dismiss()
        dialog = null
    }

    fun show(path: String, done: (Boolean, String?) -> Unit) {
        if (pending != null) {
            done(false, activity.sugarString(R.string.ws_finish_current))
            return
        }
        val bridge = SugarRuntime.activeProjects
        if (bridge == null || path.length !in 1..4095 || path.any(Char::isISOControl)) {
            done(false, activity.sugarString(R.string.ws_service_or_path))
            return
        }
        val request = Any()
        pending = request
        fun finish(removed: Boolean, error: String? = null) {
            if (pending !== request) return
            close()
            done(removed, error)
        }
        fun active() = pending === request && !activity.isFinishing && !activity.isDestroyed
        fun message(e: Throwable): String {
            val detail = (e as? IllegalStateException)?.message?.takeIf { it.isNotBlank() }?.take(240)
                ?: return activity.sugarString(R.string.ws_service_connection)
            val resource = when (detail) {
                "目录已变化或确认已过期，请重新选择。" -> R.string.ws_changed
                "文件夹里还有内容。只能删除空文件夹。" -> R.string.ws_nonempty
                "此路径受保护，不能删除。" -> R.string.ws_protected
                "该文件夹与已登记的工作区重叠，不能在这里删除。" -> R.string.ws_registered_overlap
                "请选择实际目录。路径中不能含有符号链接。" -> R.string.ws_symlink
                "工作区尚未就绪，请稍后重试。" -> R.string.ws_not_ready
                "未知的文件夹操作。" -> R.string.ws_unknown_operation
                "无法完成文件夹操作，请刷新目录后重试。" -> R.string.ws_retry_folder
                "未授权。" -> R.string.ws_unauthorized
                "文件夹请求无效。" -> R.string.ws_invalid_request
                "目录服务尚未就绪，请重新打开选择器。" -> R.string.ws_reopen_picker
                "DSH 已重新启动，请重新选择文件夹。" -> R.string.ws_dsh_restarted
                "目录服务未能完成操作，请刷新后重试。" -> R.string.ws_refresh_retry
                "目录服务返回了无效结果。" -> R.string.ws_invalid_result
                "目录操作失败。" -> R.string.ws_action_failed
                else -> return detail
            }
            return activity.sugarString(resource)
        }
        thread(name = "sugar-inspect-folder") {
            val inspection = runCatching { bridge.inspectEmptyFolder(path) }
            activity.runOnUiThread {
                if (!active()) return@runOnUiThread
                inspection.fold({ ticket ->
                    dialog = SugarDialog.builder(activity)
                        .setTitle(activity.sugarString(R.string.ws_delete_empty))
                        .setMessage(path)
                        .setNegativeButton(activity.sugarString(R.string.ws_cancel)) { _, _ -> finish(false) }
                        .setOnCancelListener { finish(false) }
                        .setPositiveButton(activity.sugarString(R.string.ws_delete)) { _, _ ->
                            thread(name = "sugar-remove-folder") {
                                val result = runCatching { bridge.removeEmptyFolder(path, ticket) }
                                activity.runOnUiThread {
                                    if (active()) result.fold({ finish(true) }, { finish(false, message(it)) })
                                }
                            }
                        }.show()
                }, { finish(false, message(it)) })
            }
        }
    }
}

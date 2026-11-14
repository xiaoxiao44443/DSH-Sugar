package app.dsh.sugar

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.dsh.sugar.runtime.RecoverySession
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** One maintenance operation across the ordinary settings pane and the offline recovery entry. */
internal class RecoveryController(context: Context) {
    private val app = context.applicationContext
    val key = UUID.randomUUID().toString()
    var busy = false
        private set
    var viewer = false
        private set
    var touched = false
        private set
    var message = ""
        private set
    var loadingMode: String? = null
        private set
    private var host: Activity? = null
    private var render: (() -> Unit)? = null
    private var resumed = false
    private var pendingUrl: String? = null
    private var closing = false
    private var confirmation: AlertDialog? = null
    @Volatile private var exiting = false
    private val starting = AtomicReference<Thread?>()

    fun attach(activity: Activity, render: () -> Unit) {
        host = activity
        this.render = render
    }

    fun detach(activity: Activity) {
        if (host !== activity) return
        confirmation?.dismiss()
        resumed = false
        host = null
        render = null
    }

    fun resume(activity: Activity) {
        if (host !== activity) return
        resumed = true
        present()
    }

    fun pause(activity: Activity) {
        if (host === activity) resumed = false
    }

    fun action(id: String) {
        val activity = host ?: return
        if (busy || viewer || exiting || confirmation != null || !resumed || activity.isFinishing) return
        when (id) {
            "current", "builtin" -> start(id == "builtin")
            "restore", "reset" -> {
                val reset = id == "reset"
                confirmation = SugarDialog.builder(activity, 440)
                    .setTitle(activity.sugarString(if (reset) R.string.diag_reset else R.string.diag_restore))
                    .setMessage(activity.sugarString(if (reset) R.string.diag_reset_note else R.string.diag_restore_note))
                    .setNegativeButton(activity.sugarString(R.string.diag_cancel), null)
                    .setPositiveButton(activity.sugarString(R.string.diag_confirm)) { _, _ -> repair(reset) }
                    .show().also { it.setOnDismissListener { confirmation = null } }
            }
        }
    }

    fun dismissConfirmation() { confirmation?.dismiss() }

    private fun begin(mode: String? = null): Boolean {
        if (busy || viewer || exiting) return false
        if (owner != null && owner !== this) {
            update(app.sugarString(R.string.diag_busy))
            return false
        }
        owner = this
        busy = true
        touched = true
        loadingMode = mode
        update(app.sugarString(R.string.diag_wait))
        return true
    }

    private fun update(value: String) {
        message = value
        render?.invoke()
    }

    private fun progress(value: String) {
        main.post { if (busy && !closing && !exiting) update(value) }
    }

    private fun start(builtin: Boolean) {
        if (!begin(if (builtin) "builtin" else "current")) return
        serial.execute {
            starting.set(Thread.currentThread())
            try {
                if (exiting) throw InterruptedException()
                NotificationEntryActivity.migratePostedNotices(app)
                RecoveryBrowserActivity.removeAbandonedProfiles(app)
                val url = RecoverySession.start(app, builtin, ::progress)
                main.post {
                    if (exiting) cleanup("") else {
                        pendingUrl = url
                        present()
                    }
                }
            } catch (failure: Exception) {
                main.post { cleanup(errorMessage(failure)) }
            } finally {
                starting.set(null)
                Thread.interrupted()
            }
        }
    }

    private fun present() {
        val activity = host ?: return
        val url = pendingUrl ?: return
        if (activity.isFinishing || activity.isDestroyed || exiting) { cleanup(""); return }
        if (!resumed) return
        pendingUrl = null
        try {
            activity.startActivityForResult(RecoveryBrowserActivity.intent(activity, url), VIEWER)
            busy = false
            viewer = true
            loadingMode = null
            update("")
        } catch (failure: Exception) {
            cleanup(errorMessage(failure))
        }
    }

    fun viewerResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != VIEWER) return false
        if (viewer) cleanup(app.sugarString(if (resultCode == Activity.RESULT_OK) R.string.diag_closed else R.string.diag_display_failed))
        return true
    }

    private fun repair(reset: Boolean) {
        if (!begin()) return
        serial.execute {
            val result = try {
                if (reset) RecoverySession.resetConfiguration(app) else RecoverySession.restoreProgram(app, ::progress)
                app.sugarString(R.string.diag_repaired)
            } catch (failure: Exception) { errorMessage(failure) }
            main.post { finishWork(result) }
        }
    }

    fun cleanup(after: String) {
        if (closing) return
        if (owner != null && owner !== this) { finishWork(after); return }
        owner = this
        busy = true
        closing = true
        loadingMode = null
        touched = true
        viewer = false
        pendingUrl = null
        update(app.sugarString(R.string.diag_closing))
        serial.execute {
            val result = try { RecoverySession.close(app); after } catch (failure: Exception) { errorMessage(failure) }
            main.post { finishWork(result) }
        }
    }

    private fun finishWork(result: String) {
        if (owner === this) owner = null
        busy = false
        closing = false
        loadingMode = null
        viewer = false
        pendingUrl = null
        update(result)
    }

    fun requestExit() {
        dismissConfirmation()
        exiting = true
        starting.get()?.interrupt()
        if (viewer || pendingUrl != null) cleanup("")
    }

    private fun errorMessage(error: Exception) =
        error.message?.takeIf { it.isNotBlank() } ?: app.sugarString(R.string.diag_failed)

    companion object {
        private const val VIEWER = 61
        private val main = Handler(Looper.getMainLooper())
        private val serial = Executors.newSingleThreadExecutor { Thread(it, "SugarRecovery") }
        private var owner: RecoveryController? = null
        val working: Boolean get() = owner != null
        fun retained(key: String?) = owner?.takeIf { it.key == key }
        fun labels(context: Context): JSONObject {
            val result = JSONObject()
            for (id in listOf(R.string.diag_title, R.string.diag_intro, R.string.diag_group_diagnose,
                R.string.diag_group_restore, R.string.diag_current, R.string.diag_current_note, R.string.diag_builtin,
                R.string.diag_builtin_note, R.string.diag_restore, R.string.diag_restore_note, R.string.diag_reset, R.string.diag_reset_note)) {
                result.put(context.resources.getResourceEntryName(id), context.sugarString(id))
            }
            return result
        }
    }
}

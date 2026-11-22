package app.dsh.sugar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import app.dsh.sugar.runtime.MobileUseTasks
import org.json.JSONObject
import java.util.concurrent.Executors

/** The WebView identifies the visible conversation. Its task controls belong to the Activity. */
internal class SugarMobileUi(private val activity: Activity, private val openSettings: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var preview: VirtualScreenManager? = null
    private var sendToShell: ((JSONObject) -> Unit)? = null
    private var previewConversation: String? = null
    private var previewTask: String? = null
    private var openChat: ((String, (Boolean) -> Unit) -> Unit)? = null
    private var previewInk = activity.getColor(R.color.sugar_ink)
    private var operations: java.util.concurrent.ExecutorService? = null
    private var overlay: MobileTaskOverlay? = null
    private var conversation = ""
    private var foreground = false
    private var settingsVisible = false
    private var generation = 0L
    private var displayedTask = ""
    private var displayedScreen = ""
    private var showControlOnEntry = false
    private val onTasksChanged: () -> Unit = { refresh(); preview?.refresh() }
    private val onPreviewRequested: () -> Unit = { reopenPreview() }

    fun attach(container: FrameLayout, openConversation: (String, (Boolean) -> Unit) -> Unit) {
        openChat = openConversation
        overlay = MobileTaskOverlay(activity, container, ::onAction)
        operations = Executors.newSingleThreadExecutor { r -> Thread(r, "sugar-mobile-ui").apply { isDaemon = true } }
        MobileUseTasks.addUiListener(onTasksChanged)
        VirtualTakeoverOverlay.pendingPreview.addListener(onPreviewRequested)
    }

    fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) showControlOnEntry = true
        if (!value) preview?.dismiss()
        resetView()
    }

    fun settingsVisible(value: Boolean) {
        if (settingsVisible == value) return
        settingsVisible = value
        preview?.dismiss()
        resetView()
    }

    fun updateNavigation(visible: List<TaskRailRegion>, touch: List<TaskRailRegion>) {
        overlay?.updateNavigation(visible, touch)
    }

    fun palette(pageSurface: String, pageInk: String, opacity: Double) {
        overlay?.updatePalette(pageSurface, pageInk, opacity)
        previewInk = Color.parseColor(pageInk)
        preview?.updateInk(previewInk)
    }

    private fun resetView() {
        generation++
        displayedTask = ""
        displayedScreen = ""
        overlay?.clear()
        refresh()
    }

    private fun refresh() {
        if (!foreground || settingsVisible || conversation.isEmpty()) return
        val id = conversation
        val epoch = generation
        operations?.execute {
            val state = runCatching { MobileUseTasks.userAction(id, "", "status") }.getOrNull()
            main.post {
                if (epoch != generation) return@post
                displayedTask = state?.opt("taskId") as? String ?: ""
                displayedScreen = state?.optString("virtualScreenId").orEmpty()
                overlay?.render(id, state)
                reopenPreview()
            }
        }
    }

    private fun reopenPreview() {
        if (!foreground || settingsVisible || activity.isFinishing || activity.isDestroyed) return
        val live = MobileUseTasks.activeVirtualScreens()
        val pending = VirtualTakeoverOverlay.pendingPreview.take(conversation, live)
        if (pending != null) {
            showControlOnEntry = false
            // The Activity may have resumed before the return worker posted its result.
            if (preview != null && previewConversation == pending.conversation && previewTask == pending.virtualKey) return
            preview?.dismiss()
            openPreview(pending.conversation, pending.virtualKey)
            return
        }
        if (preview != null) return
        val target = live.firstOrNull {
            showControlOnEntry && it.conversation == conversation && it.controlState != "agent"
        }?.let { VirtualPreviewTarget(it.conversation, it.taskId) }
        if (target != null) {
            showControlOnEntry = false
            openPreview(target.conversation, target.virtualKey)
        }
    }

    private fun onAction(id: String, taskId: String, action: String) {
        if (!foreground || settingsVisible || !activity.hasWindowFocus() ||
            conversation != id || taskId.isEmpty() || displayedTask != taskId) return
        when (action) {
            "select", "selectVirtual", "stop" -> operations?.execute {
                MobileUseTasks.userAction(id, taskId, action)
                main.post(onTasksChanged)
            }
            "preview" -> openPreview(id, displayedScreen.ifEmpty { taskId })
            "accessibility" -> openAccessibility()
            "adb" -> openSettings("adb")
            "mainSettings" -> openSettings("mobile-main")
            "virtualSettings" -> openSettings("mobile-virtual")
        }
    }

    private fun openAccessibility() {
        runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { Toast.makeText(activity, activity.sugarString(R.string.mobile_settings_unavailable), Toast.LENGTH_SHORT).show() }
    }

    fun handle(request: JSONObject, hasFocus: Boolean, reply: (JSONObject) -> Unit) {
        val type = request.optString("type")
        sendToShell = reply
        if (type == "mobileScreenTitles") {
            preview?.receiveTitles(request)
            return
        }
        if (type == "mobileConversation") {
            val next = request.opt("conversation") as? String ?: return
            if (next.length <= 256 && conversation != next) {
                conversation = next
                if (previewConversation != null && previewConversation != next) preview?.dismiss()
                resetView()
            }
            preview?.requestTitles()
            return
        }
        if (!hasFocus && type != "mobileSettings") return
        when (type) {
            "mobileSettings", "mobilePreference" -> {
                if (type == "mobilePreference" && request.opt("enabled") is Boolean) {
                    val enabled = request.getBoolean("enabled")
                    when (request.optString("preference")) {
                        "main" -> MobileUseTasks.setMainEnabled(activity, enabled)
                        "virtual" -> MobileUseTasks.setVirtualEnabled(activity, enabled)
                        "touches" -> MobileUseTasks.setShowTouches(activity, enabled)
                    }
                }
                reply(JSONObject().put("type", "mobileSettings").put("mainEnabled", MobileUseTasks.mainEnabled(activity))
                    .put("accessibilityEnabled", MobileUseTasks.accessibilityEnabled())
                    .put("virtualEnabled", MobileUseTasks.virtualEnabled(activity)).put("showTouches", MobileUseTasks.showTouches(activity))
                    .put("rootAvailable", RootAccess.get(activity).isAvailable())
                    .put("adbConnected", WirelessAdb.get(activity).status().optBoolean("connected")))
            }
            "mobileAccessibility" -> openAccessibility()
            "mobilePreview" -> {
                if (settingsVisible && foreground) openPreview(null, null)
            }
        }
    }

    private fun openPreview(conversation: String?, task: String?) {
        if (preview != null || activity.isFinishing || activity.isDestroyed) return
        val navigate = openChat ?: return
        previewConversation = conversation
        previewTask = task
        preview = VirtualScreenManager(activity, previewInk, { sendToShell?.invoke(it) }, navigate) {
            preview = null
            previewConversation = null
            previewTask = null
        }.also { it.show(conversation, task) }
    }

    fun detach() {
        MobileUseTasks.removeUiListener(onTasksChanged)
        VirtualTakeoverOverlay.pendingPreview.removeListener(onPreviewRequested)
        generation++
        conversation = ""
        displayedTask = ""
        settingsVisible = false
        overlay?.dispose()
        overlay = null
        preview?.dismiss()
        sendToShell = null
        openChat = null
        operations?.shutdown()
        operations = null
    }
}

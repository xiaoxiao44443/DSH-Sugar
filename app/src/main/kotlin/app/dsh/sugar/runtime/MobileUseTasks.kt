package app.dsh.sugar.runtime

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.dsh.sugar.WirelessAdb
import app.dsh.sugar.RootAccess
import app.dsh.sugar.mobile.LauncherAppNames
import app.dsh.sugar.mobile.MobileScreen
import app.dsh.sugar.mobile.MobileColors
import app.dsh.sugar.mobile.MobileImages
import app.dsh.sugar.mobile.MobileTemplate
import app.dsh.sugar.mobile.ScreenProblem
import app.dsh.sugar.mobile.VirtualScreen
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal fun mobileScriptPhaseError(phase: String, code: String?, message: String?): ScreenProblem? {
    if (phase == "active") return null
    if (!code.isNullOrBlank() && !message.isNullOrBlank()) return ScreenProblem(code, message)
    return when (phase) {
        "stopped" -> ScreenProblem("stopped", "Phone control stopped for this turn. A new user request can start a new task.")
        "completed", "parked" -> ScreenProblem("completed", "This phone control task has finished.")
        else -> ScreenProblem(phase, "Screen control is currently $phase.")
    }
}

/** Command access belongs to one task. A conversation can keep its virtual display between tasks. */
internal object MobileUseTasks {
    data class Owner(val host: Any, val project: String, val root: String, val conversation: String)
    data class VirtualTask(val conversation: String, val taskId: String, val displayId: Int?, val phase: String, val project: String,
        val controlState: String = "agent", val controlError: String? = null, val controlPackage: String? = null,
        val controlForeground: Boolean? = null)
    data class NoticeTask(val id: String, val screen: String, val displayId: Int?, val parked: Boolean,
        val conversation: String, val caption: String? = null, val waiting: Boolean = false,
        val lastObservation: Long = 0L, val lastDispatch: Long = 0L, val captionAt: Long = 0L,
        val awaitingCompanion: Boolean = false, val resumedAt: Long = 0L, val controlState: String = "agent")
    private data class ScriptCaption(val text: String, val atMicros: Long)
    private class Task(val owner: Owner, val id: String, var screen: String?, var manualToken: String? = null, val turn: Long = -1) {
        @Volatile var phase = "choosing"
        var control = MobileControlGate()
        var terminalPhase: String? = null
        val companion = MobileCompanion()
        @Volatile var caption: ScriptCaption? = null
        var awaitingCompanion = false
        var resumedAt = 0L
        var error: JSONObject? = null
        var capabilities = JSONObject()
        var driver: String? = null
        var virtualKey = id
        var displayId: Int? = null
        var observationId: String? = null
        var imageMapping: MobileImages.Mapping? = null
        @Volatile var observedImage: JSONObject? = null
        var observedAt = 0L
        val scriptWaiters = AtomicInteger()
        @Volatile var dispatchedAt = 0L
        val ended get() = phase in setOf("stopped", "completed", "failed")
    }
    private class Attachment(val task: Task, val token: String) {
        @Volatile var valid = true
    }
    private val tasks = mutableMapOf<Owner, Task>()
    private val parked = mutableMapOf<Owner, Task>()
    @Volatile private var mainReservation: MobileControlGate? = null
    @Volatile private var phoneControlGeneration = 0L
    private val adbUiLock = Any()
    private var rootCommands = 0
    private fun allTasks() = tasks.values + parked.values
    private val attachments = mutableMapOf<String, Attachment>()
    private val starting = Executors.newFixedThreadPool(2) { Thread(it, "sugar-mobile-start").apply { isDaemon = true } }
    private val closing = Executors.newSingleThreadExecutor { Thread(it, "sugar-mobile-close").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var context: Context? = null
    private val uiListeners = mutableSetOf<() -> Unit>() // Accessed on the main thread.
    fun addUiListener(listener: () -> Unit) { uiListeners.add(listener) }
    fun removeUiListener(listener: () -> Unit) { uiListeners.remove(listener) }
    private val refreshUi = Runnable { uiListeners.toList().forEach { it() } }
    private val refreshNotice = Runnable {
        RuntimeForegroundService.refreshNotification()
        uiListeners.toList().forEach { it() }
    }

    private var rootObserved = false
    fun initialize(app: Context) {
        context = app.applicationContext
        synchronized(this) {
            if (!rootObserved) {
                rootObserved = true
                RootAccess.get(app).addListener { deviceChanged("main"); deviceChanged("virtual") }
            }
        }
    }
    private fun changed() {
        main.removeCallbacks(refreshNotice)
        main.post(refreshNotice)
    }
    private fun preferences(app: Context) = app.getSharedPreferences("mobile-use", Context.MODE_PRIVATE)
    fun mainEnabled(app: Context) = preferences(app).getBoolean("main", true)
    fun virtualEnabled(app: Context) = preferences(app).getBoolean("virtual", false)
    fun showTouches(app: Context) = preferences(app).getBoolean("touches", false)
    fun setShowTouches(app: Context, enabled: Boolean) = preferences(app).edit().putBoolean("touches",enabled).apply()
    fun setMainEnabled(app: Context, enabled: Boolean) = setEnabled(app, "main", enabled)
    fun setVirtualEnabled(app: Context, enabled: Boolean) = setEnabled(app, "virtual", enabled)
    fun accessibilityEnabled() = MobileScreen.available()
    fun rootAvailable() = context?.let { RootAccess.get(it).isAvailable() } == true
    fun deviceChanged(screen: String) {
        main.post {
            synchronized(this) {
                allTasks().filter { it.screen == screen && it.phase in setOf("permission","active","parked","closing","close_failed") }
                    .forEach(::refresh)
            }
            changed()
        }
    }
    @Synchronized private fun setEnabled(app: Context, screen: String, enabled: Boolean) {
        initialize(app)
        preferences(app).edit().putBoolean(screen, enabled).apply()
        if (!enabled) allTasks().filter { it.screen == screen && !it.ended }.forEach { end(it, "stopped") }
        changed()
    }
    private fun adbConnected() = context?.let { WirelessAdb.get(it).status().optBoolean("connected") } == true
    private fun adbFallbackEnabled(): Boolean = context?.let { !mainEnabled(it) && !virtualEnabled(it) } == true

    @Synchronized fun captureRootAdmission(): Long {
        check(mainReservation == null) { "用户正在接管，Root 命令已暂停；归还后请重新规划操作。" }
        return phoneControlGeneration
    }

    fun <T> withRootCommand(admission: Long, execute: () -> T): T {
        synchronized(this) {
            check(mainReservation == null && admission == phoneControlGeneration) {
                "手机控制权已改变，此 Root 命令未执行；归还后请重新规划操作。"
            }
            rootCommands++
        }
        return try { execute() } finally { synchronized(this) { rootCommands-- } }
    }

    @Synchronized fun captureUiAdmission(): Long {
        check(mainReservation == null) { "用户正在接管，手机操作已暂停。" }
        return phoneControlGeneration
    }

    fun <T> withAdbUi(owner: Owner, route: JSONObject?, action: AdbUiCommand, command: String, execute: (String) -> T): T =
        withAdbUiAdmitted(captureUiAdmission(), owner, route, action, command, execute)

    fun <T> withAdbUiAdmitted(admission: Long, owner: Owner, route: JSONObject?, action: AdbUiCommand,
        command: String, execute: (String) -> T): T = synchronized(adbUiLock) {
        check(admission == phoneControlGeneration && mainReservation == null) { "用户正在接管虚拟屏，手机操作已暂停。请等待归还后重新观察。" }
        val turn = route?.optLong("turn", -1) ?: -1
        val task = synchronized(this) {
            val current = tasks[owner] ?: parked[owner]
            val active = AdbScreenFallback.usesSelectedScreen(turn, current?.turn, current?.phase, current?.error?.optString("message"))
            check(adbConnected()) { "请先在 Sugar 中连接本机无线调试。" }
            current?.takeIf { active }
        } ?: return@synchronized execute(command)

        // Keep input ordered with mobile_use. Stopping the task may still revoke a queued action.
        synchronized(task) {
            synchronized(this) {
                check(tasks[owner] === task && task.phase == "active" && stillOpen(task) && adbConnected()) {
                    "本轮的屏幕操作已结束或 ADB 已断开。"
                }
            }
            check(task.control.state == "agent") { "用户正在接管，手机操作已暂停。" }
            val version = task.control.generation
            val virtual = task.screen == "virtual"
            val routed = if (virtual) {
                check(action != AdbUiCommand.FORCE_STOP) { "强制停止会影响该应用的所有屏幕，无法限定在所选虚拟屏。" }
                val bound = task.displayId?.let { AdbScreenFallback.virtualInput(command, it) }
                    ?: error("当前已选择虚拟屏。此 ADB 命令无法限定在该屏幕，可能影响主屏或整台手机。请使用 mobile_use，或发送仅作用于虚拟屏的单条 adb shell input 命令。")
                val focus = VirtualScreen.execute(task.virtualKey, "check_foreground", JSONObject()) { task.phase == "active" && task.control.allows(version) }
                check(focus.optBoolean("ok")) {
                    focus.optJSONObject("error")?.optString("message") ?: "无法确认所选虚拟屏有前台应用。"
                }
                bound
            } else command
            check(task.phase == "active" && task.control.allows(version) && mainReservation == null) { "手机控制权已改变，请归还后重新观察。" }
            check(!task.control.needsObservation) { "接管已归还，请先使用 mobile_use 重新观察屏幕。" }
            val changesScreen = virtual || !AdbScreenFallback.readsScreen(command)
            try { execute(routed) } finally {
                if (changesScreen) {
                    task.observationId = null
                    task.imageMapping = null
                    task.observedImage = null
                }
            }
        }
    }

    fun virtualSupported() = Build.VERSION.SDK_INT >= 34
    private fun failure(code: String, message: String) = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))
    private fun state(task: Task?, missingId: String? = null) = JSONObject().apply {
        put("ok", true)
        put("taskId", task?.id ?: missingId ?: JSONObject.NULL)
        put("phase", task?.phase ?: if (missingId.isNullOrEmpty()) "idle" else "stopped")
        put("screen", task?.screen ?: JSONObject.NULL)
        put("controlState", task?.control?.state ?: "agent")
        put("controlGeneration", task?.control?.generation ?: 0L)
        task?.control?.error?.let { put("controlError", it) }
        task?.displayId?.let { put("displayId", it); put("virtualScreenId", task.virtualKey) }
        put("accessibilityEnabled", accessibilityEnabled())
        put("mainEnabled", context?.let(::mainEnabled) ?: true)
        put("virtualEnabled", context?.let(::virtualEnabled) ?: false)
        put("virtualAvailable", virtualSupported())
        put("adbConnected", adbConnected())
        put("rootAvailable", rootAvailable())
        put("rootEnabled", context?.let { RootAccess.get(it).status().optBoolean("enabled") } == true)
        task?.error?.let { put("error", it) }
        task?.driver?.let { put("driver", it) }
    }
    private fun stillOpen(task: Task) = when (task.screen) {
        "main" -> MobileScreen.isOpen(task.id)
        "virtual" -> VirtualScreen.isOpen(task.virtualKey)
        else -> false
    }
    private fun release(handle: String) { attachments.remove(handle)?.valid = false }
    private fun end(task: Task, phase: String) {
        if (task.ended || task.phase == "closing" || (task.phase == "close_failed" && phase == "completed")) return
        task.companion.close()
        task.caption = null
        task.observedImage = null
        task.imageMapping = null
        task.observationId = null
        task.manualToken = null
        attachments.entries.filter { it.value.task === task }.map { it.key }.forEach(::release)
        if (phase == "completed" && task.screen == "virtual" && task.phase == "active" && stillOpen(task)) {
            tasks.remove(task.owner, task)
            parked[task.owner] = task
            task.phase = "parked"
        } else if (task.screen == "virtual" && VirtualScreen.hasSession(task.virtualKey)) {
            closeVirtualTask(task, phase)
        } else {
            finishEnd(task, phase)
        }
        changed()
    }

    /** Called with this object's lock held. Never wait for native task migration on the UI thread. */
    private fun closeVirtualTask(task: Task, phase: String) {
        if (!task.control.beginClose()) return
        task.terminalPhase = task.terminalPhase ?: phase
        task.phase = "closing"
        val version = task.control.generation
        if (task.error?.optString("code") in setOf("close_restore_failed", "close_cleanup_failed", "close_reply_unavailable", "screen_busy")) task.error = null
        closing.execute {
            // Queue closes so Stop All can safely return several displays without competing reservations.
            synchronized(this) {
                if (task.phase != "closing" || task.control.generation != version) return@execute
                if (rootCommands > 0 || (mainReservation != null && mainReservation !== task.control) || tasks.values.any {
                        it !== task && it.screen == "main" && it.phase in setOf("starting", "active") }) {
                    val message = "Another phone operation is using the main screen. Finish it, then retry closing this virtual screen."
                    task.error = JSONObject().put("code", "screen_busy").put("message", message)
                    task.control.closeFailed(version, message)
                    task.phase = "close_failed"
                    changed()
                    return@execute
                }
                phoneControlGeneration++
                mainReservation = task.control
            }
            val result = synchronized(adbUiLock) {
                synchronized(task) { VirtualScreen.closeAndAwait(task.virtualKey) }
            }
            synchronized(this) {
                if (task.phase != "closing" || task.control.generation != version) return@synchronized
                if (result.optBoolean("closed") || !VirtualScreen.hasSession(task.virtualKey)) {
                    if (!result.optBoolean("ok")) task.error = result.optJSONObject("error")
                    finishEnd(task, task.terminalPhase ?: phase)
                } else {
                    val problem = result.optJSONObject("error") ?: JSONObject().put("code", "close_failed")
                        .put("message", "The original app could not be returned. The virtual screen is retained; retry closing it in Sugar.")
                    task.error = problem
                    task.control.closeFailed(version, problem.optString("message"))
                    task.phase = "close_failed"
                }
                changed()
            }
        }
    }

    private fun finishEnd(task: Task, phase: String) {
        parked.remove(task.owner, task)
        task.control.close()
        if (mainReservation === task.control) mainReservation = null
        task.phase = phase
        if (task.screen == "main") MobileScreen.close(task.id)
    }

    private fun refresh(task: Task) {
        if (task.ended || task.screen == null) return
        if (task.phase in setOf("closing", "close_failed")) {
            if (!VirtualScreen.hasSession(task.virtualKey)) finishEnd(task, task.terminalPhase ?: "stopped")
            return
        }
        if (task.phase == "active" || task.phase == "parked") {
            if (!stillOpen(task)) disconnected(task)
            return
        }
        if (task.phase == "starting") return
        val app = context ?: return
        val virtual = task.screen == "virtual"
        if (virtual && !virtualSupported()) {
            task.error = failure("unsupported", "Virtual screen control requires Android 14 or later.").getJSONObject("error")
            end(task, "failed")
            return
        }
        if (!(if (virtual) virtualEnabled(app) else mainEnabled(app))) { task.phase = "disabled"; return }
        val root = RootAccess.get(app).isAvailable()
        val transport = if (virtual && !root) WirelessAdb.get(app).currentTransport() else null
        if (virtual) {
            val pendingClose = parked[task.owner]?.takeIf { it.phase in setOf("closing", "close_failed") }
            if (pendingClose != null) { task.phase = "busy"; return }
            val previous = parked[task.owner]
            if (previous != null) {
                if (stillOpen(previous)) {
                    parked.remove(task.owner, previous)
                    task.virtualKey = previous.virtualKey
                    task.displayId = previous.displayId
                    task.capabilities = previous.capabilities
                    task.driver = previous.driver
                    task.control = previous.control
                    previous.phase = "completed"
                    task.phase = "active"
                    changed()
                    return
                }
                end(previous, "stopped")
                if (previous.hasDisplay) { task.phase = "busy"; return }
            }
        }
        if (if (virtual) transport == null && !root else !accessibilityEnabled() && !root) { task.phase = "permission"; return }
        if (!virtual && (mainReservation != null || tasks.values.any { it !== task && it.screen == "main" && it.phase in setOf("starting", "active") })) {
            task.phase = "busy"
            return
        }
        task.phase = "starting"
        changed()
        starting.execute {
            if (!synchronized(this) { task.phase == "starting" }) return@execute
            val opened = try {
                if (virtual) VirtualScreen.open(app, task.id, transport, { task.phase == "starting" })
                else MobileScreen.open(app, task.id) { task.phase == "starting" }
            } catch (e: Exception) { failure("start_failed", e.localizedMessage ?: "Could not start screen control.") }
            synchronized(this) {
                if (task.phase != "starting") {
                    if (tasks.values.none { it !== task && it.id == task.id && !it.ended }) {
                        if (virtual) VirtualScreen.close(task.id) else MobileScreen.close(task.id)
                    }
                } else if (opened.optBoolean("ok")) {
                    task.capabilities = opened.optJSONObject("capabilities") ?: JSONObject()
                    task.driver = opened.optString("driver").takeIf { it.isNotBlank() }
                    task.displayId = opened.optInt("display_id", -1).takeIf { virtual && it > 0 }
                    task.phase = "active"
                    changed()
                } else {
                    task.error = opened.optJSONObject("error")
                    end(task, "failed")
                }
            }
        }
    }
    private fun disconnected(task: Task) {
        val message = if (task.screen == "main") "The main screen connection was lost."
            else "The selected virtual display disconnected."
        task.error = JSONObject().put("code", "screen_disconnected")
            .put("message", "$message Phone control stopped for this turn. A new user request can start a new task.")
        end(task, "stopped")
    }
    private fun phaseFailure(task: Task): JSONObject? =
        mobileScriptPhaseError(task.phase, task.error?.optString("code"), task.error?.optString("message"))
            ?.let { failure(it.code, it.message.orEmpty()) }
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{12,96}"))
    @JvmOverloads @Synchronized fun control(owner: Owner, id: String, method: String, screen: String, turn: Long = -1): JSONObject {
        if (owner.conversation.isBlank() || owner.conversation.length > 256 || (id.isNotEmpty() && !validId(id)))
            return failure("invalid_task", "Invalid conversation or task.")
        val current = tasks[owner]
        if (method == "begin") {
            if (!validId(id) || screen !in setOf("ask","main","virtual")) return failure("invalid_target","Choose a valid screen.")
            if (current != null && (current.id == id || !current.ended)) return act(current, "status")
            parked[owner]?.takeIf { it.id == id || it.phase in setOf("closing", "close_failed") }?.let { return state(it) }
            if (allTasks().any { it.id == id || it.virtualKey == id }) return failure("invalid_task","Task id is already in use.")
            if (turn >= 0 && current?.turn == turn && current.phase in setOf("stopped", "failed")) return state(current)
            if (screen == "ask" && adbFallbackEnabled()) {
                val route = state(null)
                return route.put("phase", if (route.optBoolean("adbConnected")) "adb" else "adb_disconnected")
                    .put("screen", "adb")
                    .put("driver", "adb")
            }
            val task = Task(owner, id, screen.takeUnless { it == "ask" }, turn = turn)
            tasks[owner] = task
            refresh(task)
            changed()
            return state(task)
        }
        // A late end/stop for a completed turn must never close its retained display.
        if (id.isNotEmpty() && parked[owner]?.id == id) return state(parked[owner])
        if (current == null || (id.isNotEmpty() && current.id != id)) return state(null, id)
        return act(current, method)
    }
    private fun act(task: Task, method: String): JSONObject {
        val previousPhase = task.phase
        val previousScreen = task.screen
        when (method) {
            "select","selectVirtual" -> if (task.phase == "choosing") task.screen = if (method == "select") "main" else "virtual"
            "stop" -> end(task, "stopped")
            "finish" -> end(task, "completed")
            "wake" -> if (task.phase == "active") task.companion.wake()
            "hold", "resume" -> if (task.phase == "active" && task.awaitingCompanion != (method == "hold")) {
                task.awaitingCompanion = method == "hold"
                if (!task.awaitingCompanion) task.resumedAt = System.nanoTime() / 1000L
                changed()
            }
            "status" -> Unit
            else -> return failure("unsupported", "Unknown mobile task operation.")
        }
        refresh(task)
        if (task.phase != previousPhase || task.screen != previousScreen) changed()
        return state(task)
    }
    @Synchronized fun userAction(conversation: String, taskId: String, method: String): JSONObject {
        if (method !in setOf("status","select","selectVirtual","stop") || (taskId.isEmpty() && method != "status"))
            return failure("invalid_task", "Task id required.")
        val current = tasks.values.singleOrNull { it.owner.conversation == conversation && (taskId.isEmpty() || it.id == taskId) }
        val task = current?.takeUnless { it.ended } ?: parked.values.singleOrNull {
            it.owner.conversation == conversation && (taskId.isEmpty() || it.id == taskId)
        } ?: current
        return if (task != null) act(task, method) else state(null, taskId.takeIf { it.isNotEmpty() })
    }
    private val Task.hasDisplay get() = screen == "virtual" && phase in setOf("starting", "active", "parked", "closing", "close_failed")
    @Synchronized fun activeVirtualScreens(): List<VirtualTask> = allTasks().filter { it.hasDisplay }
        .map { VirtualTask(it.owner.conversation, it.virtualKey, it.displayId, it.phase, it.owner.project, it.control.state, it.control.error, it.control.packageName, it.control.foregroundAt(SystemClock.elapsedRealtime())) }
    @Synchronized fun stopVirtualScreen(conversation: String, taskId: String) {
        allTasks().singleOrNull { it.hasDisplay && it.owner.conversation == conversation && it.virtualKey == taskId }
            ?.let { end(it, "stopped") }
    }
    @Synchronized fun activeTasks(): List<NoticeTask> = allTasks().filter { it.phase in setOf("active", "parked", "closing", "close_failed") }
        .map { task ->
            val caption = task.caption
            NoticeTask(task.id, task.screen!!, task.displayId, task.phase == "parked", task.owner.conversation,
                caption?.text, task.scriptWaiters.get() > 0, task.observedAt, task.dispatchedAt, caption?.atMicros ?: 0L,
                task.awaitingCompanion, task.resumedAt, task.control.state)
        }
    @Synchronized fun stopFromNotice(id: String) { allTasks().find { it.id == id }?.let { end(it, "stopped") } }
    @Synchronized fun stopAll() { allTasks().forEach { end(it, "stopped") } }
    @Synchronized fun remove(host: Any, keep: (Owner)->Boolean) {
        allTasks().filter { it.owner.host === host && !keep(it.owner) }.forEach { task ->
            end(task, "stopped")
            // Keep a failed close visible and retryable even if its DSH host has gone away.
            if (!task.hasDisplay) tasks.remove(task.owner, task)
        }
    }
    @Synchronized fun revoke(token: String) {
        attachments.filterValues { it.token == token }.keys.toList().forEach(::release)
        tasks.values.filter { !it.ended && it.manualToken == token }.forEach { end(it, "completed") }
    }
    fun preview(conversation: String?, taskId: String?): JSONObject {
        val task = synchronized(this) {
            val active = allTasks().filter { it.hasDisplay && it.phase != "starting" }
            if (conversation == null && taskId == null) {
                if (active.size > 1) return failure("select_screen", "Choose a virtual screen to preview.")
                active.singleOrNull()
            } else active.singleOrNull { it.owner.conversation == conversation && (it.id == taskId || it.virtualKey == taskId) }
        }
            ?: return failure("inactive", "No active virtual screen.")
        controlFailure(task)?.let { return it }
        val version = task.control.generation
        return VirtualScreen.execute(task.virtualKey, "preview", JSONObject()) { task.control.allows(version) }
    }
    private fun controlFailure(task: Task): JSONObject? {
        if (task.control.state == "agent" && (task.screen != "main" || mainReservation == null)) return null
        return failure("user_control", "The user is controlling an app on the main screen. Phone operations are paused. Wait for return, then observe again; never replay old actions.")
            .put("controlState", task.control.state)
    }

    /** Called only while the user-control overlay is visible. Late replies never affect a replacement lease. */
    fun refreshVirtualForeground(conversation: String, virtualKey: String, appearanceBounds: IntArray? = null): JSONObject? {
        val gate = synchronized(this) {
            allTasks().singleOrNull { it.hasDisplay && it.phase in setOf("active", "parked") &&
                it.owner.conversation == conversation && it.virtualKey == virtualKey && it.control.state == "user" }
                ?.control ?: return null
        }
        val version = gate.generation
        val result = VirtualScreen.foregroundStatus(virtualKey, appearanceBounds) {
            gate.generation == version && gate.state == "user"
        }
        val foreground = if (result.optBoolean("ok")) result.opt("task_foreground") as? Boolean else null
        return synchronized(this) {
            val live = allTasks().any { it.hasDisplay && it.virtualKey == virtualKey && it.control === gate }
            if (!live || gate.generation != version || gate.state != "user") return@synchronized null
            if (gate.recordForeground(version, foreground, SystemClock.elapsedRealtime())) {
                // Changing apps changes the button only; no notification or model wake-up is needed.
                main.removeCallbacks(refreshUi)
                main.post(refreshUi)
            }
            // Native display colors stay in this local UI path; no image or model event is produced.
            result.optJSONObject("appearance").takeIf { result.optBoolean("ok") }
        }
    }

    /** UI-only: a lease cannot target an arbitrary Android task or migrate another conversation. */
    fun takeoverVirtualScreen(conversation: String, virtualKey: String) = transferScreen(conversation, virtualKey, false)
    fun restoreVirtualScreen(conversation: String, virtualKey: String) = transferScreen(conversation, virtualKey, true)

    /** Brings back the exact human-controlled Android task. It never launches a replacement app. */
    fun focusVirtualScreen(conversation: String, virtualKey: String): JSONObject {
        val task = synchronized(this) {
            val selected = allTasks().singleOrNull { it.hasDisplay &&
                it.owner.conversation == conversation && it.virtualKey == virtualKey }
                ?: return failure("inactive", "This virtual screen is no longer available.")
            if (!selected.control.beginFocus()) return failure("screen_busy", "The app is not ready to be brought back.")
            changed()
            selected
        }
        val gate = task.control
        val version = gate.generation
        val result = synchronized(adbUiLock) {
            synchronized(task) {
                VirtualScreen.focus(task.virtualKey) { gate.generation == version && gate.state == "focusing" }
            }
        }
        // Focusing is never consent to give control back, even if Android moved the task elsewhere.
        if (result.optString("control_state") == "agent") {
            result.put("ok", false).put("control_state", "unknown")
                .put("error", JSONObject().put("code", "task_changed")
                    .put("message", "The task is no longer on the main screen. Choose Return to verify and hand it back."))
        }
        return completeTransfer(gate, version, result, true)
    }

    private fun transferScreen(conversation: String, virtualKey: String, restore: Boolean): JSONObject {
        val task = synchronized(this) {
            val selected = allTasks().singleOrNull { it.hasDisplay && it.phase != "starting" &&
                it.owner.conversation == conversation && it.virtualKey == virtualKey }
                ?: return failure("inactive", "This virtual screen is no longer available.")
            if (!restore && rootCommands > 0)
                return failure("screen_busy", "A Root command is still running. Finish or stop it before taking over the screen.")
            if (!restore && (mainReservation != null || tasks.values.any {
                    it.screen == "main" && it.phase in setOf("starting", "active") }))
                return failure("screen_busy", "Another phone task is using the main screen.")
            if (!selected.control.begin(restore)) return failure("screen_busy", "A control transfer is already pending or is not needed.")
            phoneControlGeneration++
            mainReservation = selected.control
            selected.observationId = null
            selected.imageMapping = null
            selected.observedImage = null
            selected.companion.wake()
            changed()
            selected
        }
        val gate = task.control
        val version = gate.generation
        // Already dispatched gestures finish before migration. Queued requests lose their generation.
        val result = synchronized(adbUiLock) {
            synchronized(task) {
                VirtualScreen.transfer(task.virtualKey, restore) { gate.generation == version && gate.state != "closed" }
            }
        }
        return completeTransfer(gate, version, result, restore)
    }

    private fun completeTransfer(gate: MobileControlGate, version: Long, result: JSONObject, endMissing: Boolean): JSONObject =
        synchronized(this) {
            val message = result.optJSONObject("error")?.optString("message")
            val accepted = gate.complete(version, result.optString("control_state").takeIf { it.isNotBlank() }, message)
            if (accepted) gate.recordPackage(version, result.optString("package").takeIf { it.isNotBlank() })
            if (gate.state == "agent" && mainReservation === gate) mainReservation = null
            val live = allTasks().singleOrNull { it.hasDisplay && it.control === gate }
            // Only a positively verified missing task ends takeover. Query failures remain recoverable.
            if (endMissing && accepted && live != null && result.optJSONObject("error")?.optString("code") == "task_gone") {
                live.error = result.getJSONObject("error")
                end(live, "stopped")
                return@synchronized state(live).put("ok", false)
            }
            live?.companion?.wake()
            changed()
            if (live == null) failure("inactive", "This virtual screen has closed.")
            else state(live).put("ok", accepted && result.optBoolean("ok")).apply {
                result.optJSONObject("error")?.let { put("error", it) }
            }
        }

    /** A borrow may change the main display, so it shares the raw-ADB dispatch lock. */
    private inline fun <T> withPhoneDispatchLock(task: Task, mayBorrow: Boolean, action: () -> T): T =
        if (mayBorrow) synchronized(adbUiLock) { synchronized(task) { action() } }
        else synchronized(task) { action() }

    private fun launchVirtual(task: Task, method: String, input: JSONObject, allowed: () -> Boolean): JSONObject {
        val mayBorrow = synchronized(this) {
            val free = allowed() && rootCommands == 0 && mainReservation == null && tasks.values.none {
                it.screen == "main" && it.phase in setOf("starting", "active")
            }
            if (free) {
                phoneControlGeneration++
                mainReservation = task.control
            }
            free
        }
        // Host-owned permission, never trust a script-supplied value of this field.
        input.put("borrow_allowed", mayBorrow)
        val version = task.control.generation
        return try {
            val result = VirtualScreen.execute(task.virtualKey, method, input, allowed)
            val uncertain = (result.has("control_state") && result.optString("control_state") != "agent") ||
                (mayBorrow && !result.optBoolean("ok") && result.optString("dispatch") == "unknown" && result.optString("verification") == "unknown")
            if (uncertain) synchronized(this) {
                if (task.control.uncertainDispatch(version, result.optJSONObject("error")?.optString("message"))) {
                    task.control.recordPackage(task.control.generation, result.optString("package").takeIf { it.isNotBlank() })
                    task.observationId = null
                    task.imageMapping = null
                    task.observedImage = null
                    task.companion.wake()
                    result.put("control_state", "unknown")
                    changed()
                }
            }
            result
        } finally {
            synchronized(this) {
                if (mayBorrow && mainReservation === task.control && task.control.state == "agent") mainReservation = null
            }
        }
    }

    fun command(owner: Owner, token: String, manual: Boolean, boundTask: String, request: JSONObject, canceled: ()->Boolean): JSONObject {
        val method = request.getString("method")
        val args = request.optJSONObject("args") ?: JSONObject()
        val feedback = args.optJSONObject("feedback")
        if (args.has("feedback") && (method !in setOf("tap", "long_press", "swipe", "drag") ||
                feedback == null || !validFeedback(feedback)))
            return failure("invalid_argument", "Gesture feedback accepts a display crop and a positive integer max_width.")
        val key = request.optString("sessionId")
        val attachment = synchronized(this) {
            if (canceled()) return failure("session_closed","This command has ended.")
            if (method == "open") {
                val target = args.optString("screen", "auto")
                if (target !in setOf("auto","main","virtual")) return failure("invalid_target","Unknown target screen.")
                var task = tasks[owner] ?: parked[owner]?.takeIf { it.id == boundTask }
                if ((task == null || task.ended) && manual && target != "auto") {
                    task = Task(owner, UUID.randomUUID().toString(), target, token)
                    tasks[owner] = task
                }
                if (task == null) return if (boundTask.isEmpty()) failure("task_required","Start mobile_use in this conversation first.")
                    else failure("session_closed", "The phone task selected for this command no longer exists.")
                if (!manual && boundTask != task.id)
                    return failure("stale_task","Start a fresh command in the conversation that selected this screen.")
                if (target != "auto" && target != task.screen) return failure("wrong_screen","The selected task uses a different screen.")
                refresh(task)
                phaseFailure(task)?.let { return it }
                val id = UUID.randomUUID().toString()
                attachments[id] = Attachment(task, token)
                return state(task).put("sessionId", id).put("capabilities",task.capabilities)
            }
            val entry = attachments[key]?.takeIf { it.valid && it.task.owner == owner && it.token == token }
            if (entry == null) {
                val previous = (tasks[owner] ?: parked[owner])?.takeIf { it.id == boundTask }
                previous?.let(::phaseFailure)?.let { return it }
                return failure("session_closed","This script's screen session has ended.")
            }
            if (method == "close") {
                release(key)
                if (entry.task.manualToken == token && attachments.values.none { it.task === entry.task }) end(entry.task, "completed")
                return JSONObject().put("ok",true)
            }
            if (method == "interrupt") { end(entry.task,"stopped"); return state(entry.task) }
            refresh(entry.task)
            phaseFailure(entry.task)?.let { return it }
            entry
        }
        val task = attachment.task
        val version = task.control.generation
        val attached = { attachment.valid && !canceled() }
        val allowed = { attached() && task.control.allows(version) && (task.screen != "main" || mainReservation == null) }
        if (method == "companion_wait") {
            val timeout = args.getLong("timeout_ms")
            if (timeout > 0) task.scriptWaiters.incrementAndGet()
            return try { task.companion.await(timeout, attached).put("controlState", task.control.state) }
            catch (e: ScreenProblem) { failure(e.code, e.message.orEmpty()) }
            finally { if (timeout > 0) task.scriptWaiters.decrementAndGet() }
        }
        // Script processes may change between capturing an image and acting on a model's answer.
        // Keep the mapping with this task. Stopping the task never waits for a screenshot.
        return withPhoneDispatchLock(task, task.screen == "virtual" && method in setOf("launch", "open_app")) {
            try {
                if (!attached()) return@withPhoneDispatchLock failure("session_closed", "This task has ended.")
                controlFailure(task)?.let { return@withPhoneDispatchLock it }
                if (!allowed()) return@withPhoneDispatchLock failure("stale_observation", "Phone control changed. Observe the screen again after it is returned.")
                when (method) {
                    "companion_status" -> {
                        val text = (args.opt("text") as? String)?.trim()
                            ?: return@withPhoneDispatchLock failure("invalid_argument", "Expected a short status string.")
                        if (text.length > 240) return@withPhoneDispatchLock failure("invalid_argument", "Status exceeds 240 characters.")
                        // Repeating a status can reclaim it after a different tool description.
                        task.caption = ScriptCaption(text, System.nanoTime() / 1000L)
                        changed()
                        return@withPhoneDispatchLock JSONObject().put("ok", true)
                    }
                    "companion_watch" -> return@withPhoneDispatchLock task.companion.watch(args.getLong("interval_ms")) { current ->
                        val captureVersion = task.control.generation
                        val captureAllowed = { current() && task.control.allows(captureVersion) &&
                            (task.screen != "main" || mainReservation == null) }
                        controlFailure(task) ?: if (task.screen == "main") MobileScreen.preview(task.id, captureAllowed)
                        else VirtualScreen.execute(task.virtualKey, "preview", JSONObject(), captureAllowed)
                    }
                    "companion_history" -> return@withPhoneDispatchLock task.companion.read(args)
                }
                if (task.control.needsObservation && method != "observe")
                    return@withPhoneDispatchLock failure("stale_observation", "The phone has been returned by the user. Observe it again before acting; do not replay old actions.")
                if (method == "check") {
                    val expected = args.optString("observation_id")
                    return@withPhoneDispatchLock if (expected.isNotEmpty() && (task.imageMapping?.observationId != expected ||
                        SystemClock.elapsedRealtime() - task.observedAt > 60_000))
                        failure("stale_observation", "The visual observation expired or was replaced.")
                    else JSONObject().put("ok",true).put("taskId",task.id).put("screen",task.screen)
                }
                if (method == "find_template" || method == "sample_colors") {
                    val id = args.getString("observation_id")
                    val image = task.observedImage
                    if (image == null || task.imageMapping?.observationId != id ||
                        SystemClock.elapsedRealtime() - task.observedAt > 60_000)
                        return@withPhoneDispatchLock failure("stale_observation", "Observe the target screen again.")
                    val result = if (method == "find_template") MobileTemplate.find(image,args) else MobileColors.read(image,args)
                    return@withPhoneDispatchLock if (allowed()) result.put("observation_id",id) else failure("session_closed", "This task has ended.")
                }
                val observation = method == "observe"
                if (!observation && args.has("observation_id") && args.optString("observation_id") != task.observationId)
                    return@withPhoneDispatchLock failure("stale_observation", "Observe the selected screen again in this task.")
                val input = when {
                    method == "open_app" -> {
                        val app = context ?: return@withPhoneDispatchLock failure("session_closed", "Sugar is no longer available.")
                        val name = args.opt("name") as? String
                            ?: return@withPhoneDispatchLock failure("invalid_argument", "Supply the app's launcher name.")
                        val target = LauncherAppNames.resolve(app.packageManager, name)
                        if (!allowed()) return@withPhoneDispatchLock failure("session_closed", "This task has ended.")
                        if (!target.getBoolean("ok")) return@withPhoneDispatchLock target
                        target.put("owner_uid", android.os.Process.myUid())
                    }
                    observation -> args
                    else -> MobileImages.forAction(args, task.imageMapping)
                }
                val result = if (task.screen == "main") MobileScreen.execute(task.id, method, input, allowed)
                    else if (method in setOf("launch", "open_app")) launchVirtual(task, method, input, allowed)
                    else VirtualScreen.execute(task.virtualKey, method, input, allowed)
                if (!allowed()) {
                    val interrupted = controlFailure(task) ?: failure("stale_observation", "Phone control changed during this operation. Observe again; do not retry the old action.")
                    if (result.has("dispatch")) interrupted.put("dispatch", result.optString("dispatch"))
                    return@withPhoneDispatchLock interrupted.put("verification", "unknown")
                }
                if (method == "open_app" && result.optBoolean("ok")) {
                    result.put("label", input.getString("label")).put("package", input.getString("package"))
                }
                if (observation) storeObservation(task, result, args, allowed).also {
                    if (it.optBoolean("ok")) task.control.observed(version)
                } else {
                    if (result.has("dispatch")) {
                        if (result.optString("dispatch") == "sent") task.dispatchedAt = SystemClock.elapsedRealtime()
                        task.observationId = null
                        task.imageMapping = null
                        task.observedImage = null
                    }
                    if (feedback != null && result.optBoolean("ok") && result.optString("dispatch") == "sent")
                        attachFeedback(task, result, feedback, allowed)
                    result
                }
            } catch (error: ScreenProblem) {
                failure(error.code,error.message ?: error.code)
            }
        }
    }

    private fun validFeedback(options: JSONObject): Boolean {
        if (options.keys().asSequence().any { it != "crop" && it != "max_width" }) return false
        if (options.has("max_width")) {
            val width = (options.opt("max_width") as? Number)?.toDouble() ?: return false
            if (width < 1 || width != width.toInt().toDouble()) return false
        }
        if (!options.has("crop")) return true
        val crop = options.optJSONObject("crop") ?: return false
        val (left, top, right, bottom) = listOf("left", "top", "right", "bottom").map {
            (crop.opt(it) as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return false
        }
        return left < right && top < bottom
    }

    private fun storeObservation(task: Task, result: JSONObject, args: JSONObject, allowed: () -> Boolean): JSONObject {
        task.observationId = null
        task.imageMapping = null
        task.observedImage = null
        val copy = JSONObject().apply { result.keys().forEach { put(it, result.get(it)) } }
        val mapping = MobileImages.prepare(copy, args)
        return synchronized(this) {
            if (!allowed() || task.phase != "active") failure("session_closed", "The task ended while reading the display.")
            else {
                task.imageMapping = mapping
                task.observationId = copy.optString("observation_id").takeIf { it.isNotEmpty() }
                if (copy.optBoolean("ok")) task.observedAt = SystemClock.elapsedRealtime()
                task.observedImage = copy.optJSONObject("image")
                copy
            }
        }
    }

    private fun attachFeedback(task: Task, receipt: JSONObject, options: JSONObject, allowed: () -> Boolean) {
        val virtualFrame = receipt.remove("feedback") as? JSONObject
        if (receipt.has("feedback_error")) return
        val shot = try {
            if (!allowed() || task.phase != "active") failure("session_closed", "The task ended before capture.")
            else if (receipt.has("gesture") && receipt.optString("gesture") != "completed")
                failure("gesture_incomplete", "The gesture did not report completion.")
            else {
                val frame = if (task.screen == "main" && !MobileScreen.usesRoot(task.id)) mainFeedback(task, options, allowed) else virtualFrame
                if (frame == null || (frame.optBoolean("ok") && !frame.has("image")))
                    failure("feedback_unavailable", "No post-action image was received.")
                else storeObservation(task, frame, options, allowed)
            }
        } catch (error: ScreenProblem) {
            failure(error.code, error.message.orEmpty())
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            failure("feedback_unavailable", error.message ?: "The post-action frame could not be read.")
        }
        if (shot.optBoolean("ok")) receipt.put("feedback", shot)
        else receipt.put("feedback_error", shot.getJSONObject("error"))
    }

    private fun mainFeedback(task: Task, options: JSONObject, allowed: () -> Boolean): JSONObject {
        val query = JSONObject().put("tree", false).put("image", true)
            .putOpt("crop", options.opt("crop")).putOpt("max_width", options.opt("max_width"))
            .put("feedback_after_uptime_ms", SystemClock.uptimeMillis())
            .put("await_feedback_redraw", true)
        val shot = MobileScreen.execute(task.id, "observe", query, allowed)
        if (shot.optJSONObject("error")?.optString("code") != "screenshot_rate_limited") return shot
        // Only retry the capture. The gesture has already been dispatched.
        query.remove("await_feedback_redraw")
        Thread.sleep(360)
        return MobileScreen.execute(task.id, "observe", query, allowed)
    }
}

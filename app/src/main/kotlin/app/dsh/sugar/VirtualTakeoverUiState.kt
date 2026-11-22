package app.dsh.sugar

import app.dsh.sugar.runtime.MobileUseTasks.VirtualTask

internal val VirtualTask.previewCaptureAllowed get() =
    phase in setOf("active", "parked") && controlState == "agent"

/** Ordinary closing is not a user takeover. Failed closes keep a deliberate retry entry. */
internal val VirtualTask.takeoverOverlayVisible get() = when (controlState) {
    "taking_over", "user", "focusing", "restoring", "unknown" -> phase in setOf("active", "parked")
    "close_failed" -> phase == "close_failed"
    else -> false
}

internal val VirtualTask.controlAppIsForeground get() = controlState == "user" && controlForeground == true

internal enum class VirtualControlAction { FOCUS, RESTORE, RETRY_CLOSE }

internal fun VirtualTask.allowsControlAction(action: VirtualControlAction): Boolean = when (action) {
    VirtualControlAction.FOCUS -> phase in setOf("active", "parked") && controlState == "user" && controlForeground != true
    VirtualControlAction.RESTORE -> phase in setOf("active", "parked") && controlState in setOf("user", "unknown")
    VirtualControlAction.RETRY_CLOSE -> phase == "close_failed" && controlState == "close_failed"
}

internal data class VirtualPreviewTarget(val conversation: String, val virtualKey: String)

/** A return request survives Activity recreation, but never follows a replacement display lease. */
internal class PendingVirtualPreview {
    private var pending: VirtualPreviewTarget? = null
    private val listeners = mutableSetOf<() -> Unit>() // Activity and overlay callbacks use the main thread.

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** True when an already visible Activity handled the request without another Activity launch. */
    fun request(target: VirtualPreviewTarget): Boolean {
        pending = target
        listeners.toList().forEach { it() }
        return pending == null
    }

    fun take(conversation: String, live: List<VirtualTask>): VirtualPreviewTarget? {
        val target = pending ?: return null
        val screen = live.singleOrNull { it.conversation == target.conversation && it.taskId == target.virtualKey }
        if (screen == null) { pending = null; return null }
        if (conversation != target.conversation || !screen.previewCaptureAllowed) return null
        pending = null
        return target
    }
}

/** Main-thread polling admission; a hidden or replaced control cannot publish an old refresh. */
internal class VirtualForegroundPolling {
    data class Request(val target: VirtualPreviewTarget, val version: Long)
    var target: VirtualPreviewTarget? = null
        private set
    private var version = 0L
    private var inFlight: Request? = null

    fun select(next: VirtualPreviewTarget?): Boolean {
        if (target == next) return false
        target = next
        version++
        return true
    }

    fun begin(): Request? {
        if (inFlight != null) return null
        return target?.let { Request(it, version).also { request -> inFlight = request } }
    }

    fun finish(request: Request): Boolean {
        if (inFlight != request) return false
        inFlight = null
        return target == request.target && version == request.version
    }
}

package app.dsh.sugar

import app.dsh.sugar.runtime.MobileUseTasks
import java.util.concurrent.CopyOnWriteArraySet

/** A tap pauses only the progress caption for the action that was visible at that moment. */
internal data class PetSpeechPause(val state: String, val session: String?, val phase: String?, val startedAt: Long) {
    fun holds(pose: String, owner: String?, step: String?, newResult: DesktopPetActivity.Result?, now: Long) =
        newResult == null && pose == state && owner == session && step == phase && now - startedAt < DURATION_MS

    companion object { const val DURATION_MS = 4_000L }
}

/** A second line appears only while the same phone caption has been visible for a while. */
internal class PhoneCaptionProgress {
    enum class Activity { WAITING, THINKING, OBSERVED, DISPATCHED, STEP }
    data class Hint(val activity: Activity, val seconds: Long)
    data class Tick(val hint: Hint?, val nextIn: Long?)

    private var taskId: String? = null
    private var text: String? = null
    private var toolStep = 0L
    private var resumedAt = 0L
    private var since = 0L

    fun update(task: MobileUseTasks.NoticeTask?, caption: String?, hasStageCaption: Boolean,
        thinking: Boolean, now: Long, toolCaptionAt: Long = 0L): Tick {
        val resume = task?.resumedAt ?: 0L
        if (taskId != task?.id || text != caption || toolStep != toolCaptionAt || resumedAt != resume) {
            resumedAt = resume
            toolStep = toolCaptionAt
            taskId = task?.id
            text = caption
            since = now
        }
        if (task == null || caption.isNullOrBlank()) return Tick(null, null)
        val elapsed = (now - since).coerceAtLeast(0L)
        if (elapsed < 15_000L) return Tick(null, 15_000L - elapsed)
        val activity = when {
            // The agent already explained this stage; only its elapsed time adds information.
            hasStageCaption -> Activity.STEP
            task.waiting -> Activity.WAITING
            thinking -> Activity.THINKING
            task.lastObservation > 0 && task.lastObservation >= since && task.lastObservation >= task.lastDispatch -> Activity.OBSERVED
            task.lastDispatch > 0 && task.lastDispatch >= since -> Activity.DISPATCHED
            else -> Activity.STEP
        }
        return Tick(Hint(activity, elapsed / 1000L), 5000L - elapsed % 5000L)
    }
}

/** Only the current authenticated DSH host may publish the desktop pet's live status. */
internal object DesktopPetActivity {
    data class Result(val sessionId: String, val id: String, val kind: String, val text: String?, val atMicros: Long = 0L) {
        init {
            require(AgentNotifications.validId(sessionId) && AgentNotifications.validId(id))
            require(kind in setOf("answer", "reply", "operation", "error") && (text?.length ?: 0) <= 2048)
            require(kind != "reply" || (id.startsWith("say-") && !text.isNullOrBlank()))
        }

        fun supersededBy(task: MobileUseTasks.NoticeTask?): Boolean =
            kind == "reply" && atMicros > 0 && task != null && task.conversation == sessionId &&
                task.caption != null && task.captionAt > atMicros
    }

    data class Display(val pose: String, val sessionId: String?, val phase: String?,
        val result: Result?, val caption: String?, val speechState: String, val phoneStep: String? = null,
        val phoneTask: MobileUseTasks.NoticeTask? = null, val toolCaptionAt: Long = 0L) {
        val progressTask get() = phoneTask?.takeIf { !it.awaitingCompanion && result == null && phoneStep != "error" }
    }

    data class Status(val state: String = "idle", val sessionId: String? = null, val phase: String? = null,
        val result: Result? = null, val operation: Result? = null, val caption: String? = null, val phoneStep: String? = null,
        val reply: Result? = null, val captionAt: Long = 0L) {
        fun visibleResult(pose: String, session: String?): Result? = when {
            pose == "idle" -> result
            reply != null && session == reply.sessionId && pose != "asking" -> reply
            pose == state && session == sessionId && (pose == "coding" || pose == "working") -> operation
            else -> null
        }

        fun display(tasks: List<MobileUseTasks.NoticeTask>): Display {
            val phone = if (state == "asking") null else
                tasks.firstOrNull { !it.parked && it.conversation == sessionId } ?: tasks.firstOrNull { !it.parked }
            if (phone == null) return Display(state, sessionId, phase, visibleResult(state, sessionId),
                caption.takeIf { state == "asking" }, state)

            val owner = phone.conversation.takeIf(AgentNotifications::validId)
            // A companion retains the screen between replies. Older tool/script captions may
            // still arrive here while the model is waiting or after the next step has started.
            val activeReply = reply?.takeIf { it.sessionId == owner && (phone.resumedAt == 0L || it.atMicros >= phone.resumedAt) }
            if (phone.awaitingCompanion) return Display("phone", owner, phone.id, activeReply,
                null, "phone", "awaitingCompanion", phone)
            val scriptCaption = phone.caption.takeIf { phone.captionAt >= phone.resumedAt }
            if (owner == null || owner != sessionId) return Display("phone", owner, phone.id, null,
                scriptCaption?.takeIf(String::isNotBlank), "phone", phoneTask = phone)
            val step = phase?.let { "${phone.id}:$it" } ?: phone.id
            // Compare original events rather than arrival order. A delayed/replayed tool update
            // cannot replace a more recent script status, including an explicit empty status.
            val useScript = scriptCaption != null && phone.captionAt >= captionAt
            val toolIsCurrent = captionAt >= phone.resumedAt
            val detail = (if (useScript) scriptCaption else caption.takeIf { toolIsCurrent })?.takeIf(String::isNotBlank)
            val hint = phoneStep.takeIf { toolIsCurrent && (it == "error" || (detail == null && !useScript)) }
            val currentReply = activeReply?.takeUnless { it.supersededBy(phone) }
            return Display("phone", owner, step, operation?.takeIf { it.kind == "error" } ?: currentReply, detail,
                if (state == "thinking") "thinking" else "phone", hint, phone, if (!useScript && detail != null) captionAt else 0L)
        }
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val seen = LinkedHashSet<Pair<String, String>>()
    private var host: Any? = null
    @Volatile private var current = Status()

    fun status(): Status = current
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    @Synchronized fun attach(owner: Any) {
        host = owner
        update(Status(result = current.result))
    }

    @Synchronized fun detach(owner: Any) {
        if (host !== owner) return
        host = null
        update(Status(result = current.result))
    }

    @Synchronized fun publish(owner: Any, state: String, sessionId: String?, result: Result?, phase: String? = null,
        caption: String? = null, phoneStep: String? = null, captionAt: Long = 0L) {
        if (host !== owner) return
        require(state in DesktopPets.states && (sessionId == null || AgentNotifications.validId(sessionId)))
        val activeSession = sessionId.takeUnless { state == "idle" }
        require(phase == null || AgentNotifications.validId(phase))
        val fresh = result?.takeIf { seen.add(it.sessionId to it.id) }
        if (seen.size > 256) seen.remove(seen.first())
        val nextPhase = phase.takeIf { activeSession != null }
        val detail = caption?.trim()?.takeIf { activeSession != null && it.isNotEmpty() &&
            it.length <= 240 && it.none(Char::isISOControl) }
        val step = phoneStep?.takeIf { activeSession != null && (it == "screen" || it == "error") }
        val samePhase = current.sessionId == activeSession && current.state == state && current.phase == nextPhase
        val operation = fresh?.takeIf { it.id.startsWith("tool-") && it.sessionId == activeSession &&
            state in setOf("coding", "working") && (it.text != null || it.kind == "error") }
        val final = fresh?.takeIf { it.id.startsWith("turn-") } ?: current.result
        val reply = if (activeSession == null || state == "asking" || step == "error" ||
            fresh?.sessionId == activeSession && (fresh.kind == "error" || fresh.id.startsWith("turn-"))) null
        else fresh?.takeIf { it.kind == "reply" && it.sessionId == activeSession }
            ?: current.reply?.takeIf { it.sessionId == activeSession }
        update(Status(state, activeSession, nextPhase, final, operation ?: current.operation.takeIf { samePhase }, detail, step, reply, captionAt.coerceAtLeast(0L)))
    }

    @Synchronized fun dismiss(result: Result) {
        if (current.reply == result) update(current.copy(reply = null))
        else if (current.operation == result) update(current.copy(operation = null))
        else if (current.result == result) update(current.copy(result = null))
    }

    private fun update(next: Status) {
        if (next == current) return
        current = next
        listeners.forEach { it() }
    }
}

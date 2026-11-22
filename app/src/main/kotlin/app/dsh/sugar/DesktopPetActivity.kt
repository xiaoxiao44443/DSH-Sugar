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
        val phoneTask: MobileUseTasks.NoticeTask? = null, val toolCaptionAt: Long = 0L,
        val contentAtMicros: Long = 0L, val turnStartedAt: Long = 0L) {
        val progressTask get() = phoneTask?.takeIf { !it.awaitingCompanion && result == null && phoneStep != "error" }
    }

    data class Status(val state: String = "idle", val sessionId: String? = null, val phase: String? = null,
        val result: Result? = null, val operation: Result? = null, val caption: String? = null, val phoneStep: String? = null,
        val reply: Result? = null, val captionAt: Long = 0L, val turnStartedAt: Long = 0L) {
        private fun Result.isBefore(at: Long) = atMicros > 0L && at > atMicros

        fun visibleResult(pose: String, session: String?): Result? {
            if (pose == "idle") return result
            if (pose == "asking") return null
            val speech = reply?.takeIf { it.sessionId == session && !it.isBefore(captionAt) && (turnStartedAt == 0L || it.atMicros >= turnStartedAt) }
            val operation = operation?.takeIf { pose == state && session == sessionId &&
                (pose in setOf("coding", "working") || it.kind == "error") && !it.isBefore(captionAt) &&
                (turnStartedAt == 0L || it.atMicros >= turnStartedAt) }
            return if (speech == null || operation != null && speech.isBefore(operation.atMicros)) operation else speech
        }

        fun display(tasks: List<MobileUseTasks.NoticeTask>): Display {
            val phone = if (state == "asking") null else
                tasks.firstOrNull { !it.parked && it.conversation == sessionId } ?: tasks.firstOrNull { !it.parked }
            if (phone == null) {
                val visible = visibleResult(state, sessionId)
                val detail = caption.takeIf { state == "asking" || state != "idle" && captionAt >= turnStartedAt }
                return Display(state, sessionId, phase, visible, detail, state,
                    phoneStep.takeIf { state != "idle" && (it == "error" || detail == null) },
                    toolCaptionAt = captionAt.takeIf { detail != null } ?: 0L,
                    contentAtMicros = visible?.atMicros ?: captionAt.takeIf { detail != null } ?: 0L, turnStartedAt = turnStartedAt)
            }

            val owner = phone.conversation.takeIf(AgentNotifications::validId)
            val turnBoundary = turnStartedAt.takeIf { owner == sessionId } ?: 0L
            // The phone lease can outlive the turn. Publish its fresh completion immediately,
            // but never replay an older final when this screen is resumed or reused.
            val completed = result?.takeIf { it.id.startsWith("turn-") && it.sessionId == owner &&
                it.atMicros > 0L && it.atMicros > maxOf(phone.resumedAt, phone.captionAt, turnBoundary) }
            if (completed != null) return Display("phone", owner, phone.id, completed, null, "phone",
                "awaitingCompanion".takeIf { phone.awaitingCompanion }, phone,
                contentAtMicros = completed.atMicros, turnStartedAt = turnBoundary)
            // A companion retains the screen between replies. Reject content from before a
            // wake or a new turn, even if a delayed publisher delivers it after that boundary.
            val activeReply = reply?.takeIf { it.sessionId == owner &&
                (phone.resumedAt == 0L || it.atMicros >= phone.resumedAt) &&
                (turnBoundary == 0L || it.atMicros >= turnBoundary) &&
                (owner != sessionId || !it.isBefore(captionAt)) }
            if (phone.awaitingCompanion) return Display("phone", owner, phone.id, activeReply,
                null, "phone", "awaitingCompanion", phone, contentAtMicros = activeReply?.atMicros ?: 0L, turnStartedAt = turnBoundary)
            val scriptCaption = phone.caption.takeIf { phone.captionAt >= maxOf(phone.resumedAt, turnBoundary) }
            if (owner == null || owner != sessionId) return Display("phone", owner, phone.id, null,
                scriptCaption?.takeIf(String::isNotBlank), "phone", phoneTask = phone,
                contentAtMicros = phone.captionAt.takeIf { !scriptCaption.isNullOrBlank() } ?: 0L)
            val step = phase?.let { "${phone.id}:$it" } ?: phone.id
            // Compare original events rather than arrival order. An explicit empty caption
            // is also an event: it clears older text instead of revealing it again.
            val useScript = scriptCaption != null && phone.captionAt >= captionAt
            val toolIsCurrent = captionAt >= maxOf(phone.resumedAt, turnBoundary)
            val detail = (if (useScript) scriptCaption else caption.takeIf { toolIsCurrent })?.takeIf(String::isNotBlank)
            val detailAt = if (useScript) phone.captionAt else captionAt.takeIf { toolIsCurrent } ?: 0L
            val hint = phoneStep.takeIf { toolIsCurrent &&
                (it == "error" && (!useScript || captionAt >= phone.captionAt) || detail == null && !useScript) }
            val currentReply = activeReply?.takeUnless { it.isBefore(detailAt) }
            val failure = operation?.takeIf { it.kind == "error" && !it.isBefore(detailAt) &&
                (turnBoundary == 0L || it.atMicros >= turnBoundary) }
            val visible = if (currentReply == null || failure != null && currentReply.isBefore(failure.atMicros)) failure else currentReply
            return Display("phone", owner, step, visible, detail,
                if (state == "thinking") "thinking" else "phone", hint, phone,
                if (!useScript && detail != null) captionAt else 0L,
                visible?.atMicros ?: detailAt.takeIf { detail != null } ?: 0L, turnBoundary)
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
        caption: String? = null, phoneStep: String? = null, captionAt: Long = 0L, turnStartedAt: Long = 0L) {
        if (host !== owner) return
        require(state in DesktopPets.states && (sessionId == null || AgentNotifications.validId(sessionId)))
        val activeSession = sessionId.takeUnless { state == "idle" }
        require(phase == null || AgentNotifications.validId(phase))
        val sameSession = current.sessionId == activeSession && activeSession != null
        val requestedTurn = turnStartedAt.coerceAtLeast(0L)
        if (sameSession && requestedTurn > 0L && requestedTurn < current.turnStartedAt) return
        val turnBoundary = if (activeSession == null) 0L else requestedTurn.takeIf { it > 0L }
            ?: current.turnStartedAt.takeIf { sameSession } ?: 0L
        val fresh = result?.takeIf { seen.add(it.sessionId to it.id) }
        if (seen.size > 256) seen.remove(seen.first())
        val nextPhase = phase.takeIf { activeSession != null }
        val incomingAt = captionAt.coerceAtLeast(0L)
        val staleProgress = sameSession && state != "asking" && current.state != "asking" &&
            incomingAt > 0L && incomingAt < current.captionAt
        val detail = if (staleProgress) current.caption else caption?.trim()?.takeIf { activeSession != null &&
            (state == "asking" || incomingAt >= turnBoundary) && it.isNotEmpty() &&
            it.length <= 240 && it.none(Char::isISOControl) }
        val step = if (staleProgress) current.phoneStep else phoneStep?.takeIf { activeSession != null && (it == "screen" || it == "error") }
        val detailAt = if (staleProgress) current.captionAt else incomingAt
        val samePhase = sameSession && current.state == state && current.phase == nextPhase && current.turnStartedAt == turnBoundary
        val activeFresh = fresh?.takeIf { it.sessionId == activeSession && (turnBoundary == 0L || it.atMicros >= turnBoundary) }
        val operation = activeFresh?.takeIf { it.id.startsWith("tool-") &&
            (state in setOf("coding", "working") || state == "thinking" && it.kind == "error") &&
            (it.text != null || it.kind == "error") &&
            (turnBoundary == 0L || it.atMicros >= turnBoundary) }
        val final = fresh?.takeIf { it.id.startsWith("turn-") } ?: current.result?.takeUnless {
            it.sessionId == activeSession && turnBoundary > 0L && (it.atMicros == 0L || it.atMicros < turnBoundary)
        }
        val previousReply = current.reply?.takeIf { sameSession }
        val newReply = activeFresh?.takeIf { it.kind == "reply" }
        val latestReply = if (newReply != null && (previousReply == null || newReply.atMicros == 0L ||
            newReply.atMicros >= previousReply.atMicros)) newReply else previousReply
        val reply = latestReply?.takeUnless { activeSession == null || state == "asking" || step == "error" ||
            activeFresh != null && (activeFresh.kind == "error" || activeFresh.id.startsWith("turn-")) &&
                (activeFresh.atMicros == 0L || activeFresh.atMicros >= it.atMicros) ||
            it.atMicros > 0L && detailAt > it.atMicros || turnBoundary > 0L && it.atMicros < turnBoundary }
        val retainedOperation = current.operation?.takeIf { samePhase ||
            sameSession && current.turnStartedAt == turnBoundary && state in setOf("coding", "working", "thinking") &&
                it.kind == "error" && step == "error" && (it.atMicros == 0L || it.atMicros >= detailAt) &&
                (reply == null || reply.atMicros <= it.atMicros) }
        update(Status(state, activeSession, nextPhase, final, operation ?: retainedOperation, detail, step, reply, detailAt, turnBoundary))
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

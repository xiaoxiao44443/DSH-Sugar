package app.dsh.sugar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.dsh.sugar.runtime.SugarRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/** The displayed pet owns the destination. The host rechecks the session and question when sending. */
internal class PetReplySheet(private val context: Context, private val changed: () -> Unit) {
    private data class Owner(val session: String?, val pack: String, val askingPhase: String?)
    private data class Question(val id: String, val title: String, val labels: List<String>, val multiple: Boolean, val custom: Boolean)
    private val main = Handler(Looper.getMainLooper())
    private var owner: Owner? = null
    private var generation = 0
    private var opening = false
    private var sending = false
    private var cancelVoice: (() -> Unit)? = null
    private var questions = emptyList<Question>()
    private var quick = emptyList<String>()
    private var requestId: String? = null
    private var sessionTitle: String? = null
    val hasTarget: Boolean get() = owner?.session != null
    private var group = 0
    private var answers = JSONArray()
    private var microphone = false
    private var lastPayload: String? = null
    private var deliveryId = ""
    private var note: DesktopPetAnswerNote? = null
    private var expanded = false
    val view: DesktopPetAnswerNote? get() = note.takeIf { expanded }
    val prompt: String? get() {
        if (!expanded && owner?.askingPhase == null) return null
        return questions.getOrNull(group)?.title ?: sessionTitle?.takeIf { expanded }?.let {
            context.sugarString(R.string.pet_reply_to_session, it)
        }
    }

    fun bind(pack: String, status: DesktopPetActivity.Display) {
        val target = Owner(status.sessionId ?: status.result?.sessionId, pack,
            if (status.pose == "asking") status.phase.orEmpty() else null)
        if (owner == target) return
        close()
        owner = target
    }

    fun close() {
        generation++
        cancelVoice?.invoke()
        cancelVoice = null
        note = null
        expanded = false
        opening = false
        sending = false
        questions = emptyList()
        quick = emptyList()
        requestId = null
        sessionTitle = null
        group = 0
        answers = JSONArray()
        lastPayload = null
    }

    fun toggle() {
        if (opening || view != null) {
            // Keep the current page and any unsent selections while hiding its window.
            if (!sending) generation++
            opening = false
            expanded = false
            cancelVoice?.invoke()
            cancelVoice = null
            changed()
            return
        }
        if (sending) return
        val target = owner
        val session = target?.session
        val bridge = SugarRuntime.activeProjects
        if (session == null) return
        if (bridge == null) {
            message(R.string.pet_reply_unavailable)
            return
        }
        opening = true
        val ticket = ++generation
        thread(name = "SugarPetReadReply", isDaemon = true) {
            val result = runCatching { bridge.petReplyState(session) }
            main.post {
                if (ticket != generation) return@post
                opening = false
                val state = result.getOrNull()
                if (state == null || state.optString("sessionId") != session || state.optString("mode") !in setOf("quick", "question")) {
                    if (state != null) close()
                    message(R.string.pet_reply_unavailable)
                    changed()
                    return@post
                }
                sessionTitle = state.optString("sessionTitle").trim().takeIf { it.isNotEmpty() }
                val asking = state.optString("mode") == "question"
                val nextId = if (asking) state.optString("questionId").takeIf { it.isNotBlank() } else null
                val nextQuestions = if (asking) parseQuestions(state.optJSONArray("questions")) else emptyList()
                val nextQuick = if (asking) emptyList() else DesktopPetSpeech.quickReplies(context)
                if (asking && (nextId == null || nextQuestions.isEmpty())) {
                    close()
                    message(R.string.pet_reply_unavailable)
                    changed()
                    return@post
                }
                val reuse = note != null && nextId == requestId && nextQuestions == questions && nextQuick == quick
                if (!reuse) {
                    group = 0
                    answers = JSONArray()
                    lastPayload = null
                    requestId = nextId
                    questions = nextQuestions
                    quick = nextQuick
                    note = DesktopPetAnswerNote(context, target.pack, onFold = ::toggle,
                        onVoice = ::startVoice, onConfirm = ::confirm)
                }
                expanded = true
                microphone = false
                if (reuse) {
                    view?.setVoiceAvailable(false)
                    changed()
                } else showGroup()
                thread(name = "SugarPetVoiceAvailability", isDaemon = true) {
                    val available = runCatching { DesktopPetVoice.available(context) }.getOrDefault(false)
                    main.post {
                        if (ticket == generation) {
                            microphone = available
                            view?.setVoiceAvailable(microphone && questions.getOrNull(group)?.custom != false)
                            if (!available && questions.isEmpty() && quick.isEmpty()) {
                                message(R.string.pet_reply_empty)
                                close()
                            }
                            changed()
                        }
                    }
                }
            }
        }
    }

    private fun parseQuestions(data: JSONArray?): List<Question> {
        if (data == null) return emptyList()
        return (0 until data.length()).map { i ->
            val item = data.optJSONObject(i) ?: return emptyList()
            val id = item.optString("id")
            val title = item.optString("question")
            if (id.isBlank() || title.isBlank()) return emptyList()
            val options = item.optJSONArray("options") ?: JSONArray()
            val labels = (0 until options.length()).map { options.optJSONObject(it)?.optString("label") ?: return emptyList() }
            Question(id, title, labels, item.optBoolean("multiSelect"), item.optBoolean("allowCustom", true))
        }
    }

    private fun showGroup() {
        val q = questions.getOrNull(group)
        view?.bind(q?.labels ?: quick, q?.multiple == true, microphone && q?.custom != false)
        changed()
    }

    private fun startVoice() {
        val sheet = view ?: return
        if (sending || cancelVoice != null) return
        val page = group
        var active = true
        var handle: PetVoiceHandle? = null
        fun ownsPage() = view === sheet && page == group && !sending
        fun clearRecorder() {
            active = false
            cancelVoice = null
            sheet.setVoicePhase(null)
        }
        val cancel: () -> Unit = {
            if (active) {
                clearRecorder()
                handle?.cancel()
            }
        }
        cancelVoice = cancel
        val registration = DesktopPetVoice.start(context,
            onText = { text ->
                if (active) {
                    clearRecorder()
                    if (ownsPage()) {
                        sheet.setVoiceText(text)
                        changed()
                    }
                }
            },
            onError = { error ->
                if (active) {
                    clearRecorder()
                    if (ownsPage()) {
                        if (error.isNotBlank()) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        changed()
                    }
                }
            },
            onPhase = { phase ->
                if (active && ownsPage()) {
                    sheet.setVoicePhase(phase, onCancel = {
                        cancel()
                        if (ownsPage()) changed()
                    }, onDone = { handle?.done() })
                    changed()
                }
            },
            onLevel = { level -> if (active && ownsPage()) sheet.setVoiceLevel(level) },
        )
        handle = registration
        if (!active) registration.cancel()
    }

    private fun confirm(indices: List<Int>) {
        val sheet = view ?: return
        val session = owner?.session ?: return
        val bridge = SugarRuntime.activeProjects ?: return message(R.string.pet_reply_unavailable)
        if (sending || indices.isEmpty()) return
        cancelVoice?.invoke()
        cancelVoice = null
        val question = questions.getOrNull(group)
        val text: String?
        if (question == null) {
            text = quick.getOrNull(indices.first()) ?: sheet.voiceText ?: return
        } else {
            text = null
            val answer = JSONObject().put("id", question.id)
                .put("selected", JSONArray(indices.mapNotNull(question.labels::getOrNull)))
            if (question.labels.size in indices && question.custom) answer.put("custom", sheet.voiceText)
            answers.put(group, answer)
            if (group < questions.lastIndex) {
                group++
                showGroup()
                return
            }
        }
        val payload = text ?: answers.toString()
        if (payload != lastPayload) {
            deliveryId = UUID.randomUUID().toString()
            lastPayload = payload
        }
        val id = deliveryId
        val expected = requestId
        val submitted = if (question == null) null else JSONArray(answers.toString())
        val ticket = generation
        sending = true
        sheet.setInteractive(false)
        thread(name = "SugarPetSendReply", isDaemon = true) {
            val result = runCatching { bridge.sendPetReply(session, id, text, expected, submitted) }
            main.post {
                if (ticket != generation) return@post
                sending = false
                val ok = result.getOrNull()?.optBoolean("ok", true) == true
                if (ok) close() else sheet.setInteractive(true)
                if (!ok) message(R.string.pet_reply_send_error)
                changed()
            }
        }
    }

    private fun message(res: Int) {
        Toast.makeText(context, context.sugarString(res), Toast.LENGTH_SHORT).show()
    }
}

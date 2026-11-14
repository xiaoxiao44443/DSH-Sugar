package app.dsh.sugar

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import app.dsh.sugar.runtime.SugarRuntime
import java.lang.ref.WeakReference
import java.util.UUID

internal enum class PetVoicePhase { PREPARING, RECORDING, TRANSCRIBING }

internal class PetVoiceHandle(private val id: String) {
    fun cancel() = DesktopPetVoice.cancel(id)
    fun done() = DesktopPetVoice.stopRecording(id)
}

/** One foreground recording belongs to the paper note that opened it. UI callbacks run on the main thread. */
internal object DesktopPetVoice {
    private data class Request(
        val id: String,
        val text: (String) -> Unit,
        val error: (String) -> Unit,
        val phase: (PetVoicePhase) -> Unit,
        val level: (Float) -> Unit,
    )
    private var current: Request? = null
    private var recorder: WeakReference<PetVoiceCaptureActivity>? = null
    private val main = Handler(Looper.getMainLooper())

    fun available(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
            runCatching { SugarRuntime.activeProjects?.petVoiceRequest("voiceAvailable")?.optBoolean("available") == true }
                .getOrDefault(false)

    fun start(context: Context, onText: (String) -> Unit, onError: (String) -> Unit,
              onPhase: (PetVoicePhase) -> Unit, onLevel: (Float) -> Unit): PetVoiceHandle {
        current?.let { cancel(it.id) }
        val id = UUID.randomUUID().toString()
        current = Request(id, onText, onError, onPhase, onLevel)
        onPhase(PetVoicePhase.PREPARING)
        val intent = Intent(context, PetVoiceCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .putExtra("request", id)
        try {
            context.startActivity(intent)
            main.postDelayed({
                if (current?.id == id && recorder?.get() == null) finish(id, null, context.sugarString(R.string.pet_voice_failed))
            }, 5000)
        } catch (_: RuntimeException) {
            finish(id, null, context.sugarString(R.string.pet_voice_failed))
        }
        return PetVoiceHandle(id)
    }

    fun attach(id: String, activity: PetVoiceCaptureActivity): Boolean {
        if (current?.id != id) return false
        recorder = WeakReference(activity)
        return true
    }

    fun phase(id: String, value: PetVoicePhase) {
        current?.takeIf { it.id == id }?.phase?.invoke(value)
    }

    fun level(id: String, value: Float) {
        current?.takeIf { it.id == id }?.level?.invoke(value)
    }

    fun stopRecording(id: String) {
        if (current?.id == id) recorder?.get()?.stopRecording()
    }

    fun finish(id: String, text: String?, error: String) {
        val request = current?.takeIf { it.id == id } ?: return
        current = null
        recorder = null
        if (text == null) request.error(error) else request.text(text)
    }

    fun cancel(id: String) {
        val request = current?.takeIf { it.id == id } ?: return
        val activity = recorder?.get()
        current = null
        recorder = null
        activity?.finish()
        request.error("")
    }
}

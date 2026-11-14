package app.dsh.sugar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import app.dsh.sugar.runtime.SugarRuntime
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Android owns the permission prompt and foreground mic lifecycle. The pet note owns all capture controls. */
class PetVoiceCaptureActivity : Activity() {
    private lateinit var request: String
    @Volatile private var stopping = false
    @Volatile private var cancelled = false
    private var started = false
    private var answered = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        request = intent.getStringExtra("request").orEmpty()
        if (!DesktopPetVoice.attach(request, this)) { finish(); return }
        // A second touch surface would obscure the in-place recording controls on the overlay.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setWindowAnimations(0)
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(1, 1)
        setFinishOnTouchOutside(false)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 1 || isFinishing) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) record()
        else complete(null, sugarString(R.string.pet_voice_permission))
    }

    internal fun stopRecording() {
        if (started && !cancelled && !stopping) {
            stopping = true
            DesktopPetVoice.phase(request, PetVoicePhase.TRANSCRIBING)
        }
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        if (started) return
        val host = SugarRuntime.activeProjects ?: return complete(null, sugarString(R.string.pet_voice_failed))
        started = true
        thread(name = "pet-voice-capture") {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(bufferSize > 0)
                val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(bufferSize, 4096))
                val pcm = ByteArrayOutputStream()
                try {
                    if (cancelled) return@thread
                    input.startRecording()
                    check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    runOnUiThread { if (!cancelled && !stopping) DesktopPetVoice.phase(request, PetVoicePhase.RECORDING) }
                    val block = ByteArray(1280)
                    var nextLevelAt = 0
                    while (!cancelled && pcm.size() < 640000 && (!stopping || pcm.size() == 0)) {
                        val n = input.read(block, 0, minOf(block.size, 640000 - pcm.size()))
                        check(n > 0)
                        pcm.write(block, 0, n)
                        if (pcm.size() >= nextLevelAt) {
                            val level = audioLevel(block, n)
                            nextLevelAt = pcm.size() + 2560
                            runOnUiThread { if (!cancelled && !stopping) DesktopPetVoice.level(request, level) }
                        }
                    }
                } finally {
                    try { if (input.recordingState == AudioRecord.RECORDSTATE_RECORDING) input.stop() }
                    finally { input.release() }
                }
                if (cancelled) return@thread
                check(pcm.size() > 0)
                runOnUiThread { if (!cancelled) DesktopPetVoice.phase(request, PetVoicePhase.TRANSCRIBING) }
                val wav = ByteBuffer.allocate(44 + pcm.size()).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray()).putInt(36 + pcm.size()).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(pcm.size()).put(pcm.toByteArray()).array()
                if (cancelled) return@thread
                check(SugarRuntime.activeProjects === host)
                val text = host.petVoiceRequest("voiceTranscribe", Base64.encodeToString(wav, Base64.NO_WRAP))
                    .optString("text").trim().takeIf { it.isNotEmpty() }
                runOnUiThread { complete(text, sugarString(R.string.pet_voice_unheard)) }
            } catch (_: Exception) {
                runOnUiThread { complete(null, sugarString(R.string.pet_voice_failed)) }
            }
        }
    }

    // Normalize RMS and compress the range so soft speech still marks the paper.
    private fun audioLevel(pcm: ByteArray, bytes: Int): Float {
        var sum = 0.0
        val samples = bytes / 2
        if (samples == 0) return 0f
        for (i in 0 until samples) {
            val lo = pcm[2 * i].toInt() and 0xff
            val sample = ((pcm[2 * i + 1].toInt() shl 8) or lo) / 32768.0
            sum += sample * sample
        }
        return sqrt(sqrt(sum / samples) * 2.5).toFloat().coerceIn(0f, 1f)
    }

    private fun complete(text: String?, error: String) {
        if (cancelled || answered || isFinishing) return
        answered = true
        DesktopPetVoice.finish(request, text, error)
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (started && !isFinishing) finish()
    }

    override fun finish() {
        cancelled = true
        if (::request.isInitialized && !answered) DesktopPetVoice.finish(request, null, "")
        super.finish()
    }

    override fun onDestroy() {
        cancelled = true
        if (::request.isInitialized && !answered) DesktopPetVoice.finish(request, null, "")
        super.onDestroy()
    }
}

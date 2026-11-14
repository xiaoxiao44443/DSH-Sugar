package app.dsh.sugar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import app.dsh.sugar.runtime.RuntimeForegroundService
import app.dsh.sugar.runtime.SugarRuntime
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** The settings WebView previews bundled pets; only this bridge changes the system overlay. */
internal class PetSettingsBridge(private val activity: Activity, private val report: (JSONObject) -> Unit) {
    private var observed = false
    private var requestingPermission = false
    private var permissionError: String? = null
    private var answerPreview: PetAnswerPreview? = null
    private var checkingVoice = false

    fun state(): JSONObject {
        val allowed = Settings.canDrawOverlays(activity)
        val packs = JSONArray()
        for (pack in DesktopPets.packs(activity)) {
            packs.put(JSONObject().put("id", pack.id).put("name", pack.name(activity))
                .put("customBubble", pack.bubbleRes != R.drawable.sugar_pet_bubble))
        }
        val lines = JSONObject()
        for (category in DesktopPetSpeech.categories) lines.put(category, JSONArray(DesktopPetSpeech.lines(activity, category)))
        return JSONObject()
            .put("packs", packs)
            .put("selected", DesktopPets.selected(activity))
            .put("size", DesktopPets.size(activity))
            .put("allowed", allowed)
            .put("enabled", allowed && DesktopPets.enabled(activity))
            .put("openConversationOnBubbleTap", DesktopPets.openConversationOnBubbleTap(activity))
            .put("permissionPending", requestingPermission)
            .put("speech", lines)
    }

    private fun publish(error: String? = permissionError) {
        if (!observed) return
        val update = JSONObject().put("type", "petState").put("pet", state())
        if (error != null) update.put("error", error)
        report(update)
    }

    fun handle(data: JSONObject, focused: Boolean) {
        when (data.optString("type")) {
            "petStatus" -> {
                observed = true
                publish()
            }
            "petVoiceStatus" -> queryVoiceStatus()
            "petAnswerPreview" -> {
                if (!focused || activity.isFinishing || activity.isDestroyed || answerPreview != null) return
                val id = data.optString("pack", DesktopPets.selected(activity))
                val pack = DesktopPets.packs(activity).find { it.id == id } ?: return
                answerPreview = PetAnswerPreview(activity, pack) { answerPreview = null }.also { it.show() }
            }
            "petChange" -> {
                if (!focused) return
                val changes = data.optJSONObject("changes") ?: return
                (changes.opt("selected") as? String)?.let { DesktopPets.select(activity, it) }
                (changes.opt("size") as? Int)?.let { DesktopPets.setSize(activity, it) }
                (changes.opt("openConversationOnBubbleTap") as? Boolean)?.let {
                    DesktopPets.setOpenConversationOnBubbleTap(activity, it)
                }
                val enabled = changes.opt("enabled") as? Boolean
                if (enabled != null) permissionError = changeEnabled(enabled)
                publish()
            }
            "petSpeech" -> {
                if (!focused) return
                val category = data.optString("state")
                if (category !in DesktopPetSpeech.categories) return
                if (data.opt("reset") == true) {
                    DesktopPetSpeech.reset(activity, category)
                } else {
                    val values = data.optJSONArray("lines") ?: return
                    if (data.toString().length > MAX_SPEECH_PACKET) {
                        publish("speech-too-long")
                        return
                    }
                    val phrases = (0 until values.length()).map { values.opt(it) as? String ?: return }
                    DesktopPetSpeech.save(activity, category, phrases)
                }
                publish()
            }
        }
    }

    private fun queryVoiceStatus() {
        if (checkingVoice) return
        checkingVoice = true
        thread(name = "SugarPetVoiceStatus", isDaemon = true) {
            val state = if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                runCatching { SugarRuntime.activeProjects?.petVoiceRequest("voiceStatus")?.getString("state") }
                    .getOrNull() ?: "unavailable"
            } else "unavailable"
            activity.runOnUiThread {
                checkingVoice = false
                if (!activity.isDestroyed && !activity.isFinishing) {
                    report(JSONObject().put("type", "petVoiceStatus").put("state", state))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun changeEnabled(enabled: Boolean): String? {
        if (!enabled || Settings.canDrawOverlays(activity)) {
            requestingPermission = false
            DesktopPets.setEnabled(activity, enabled)
            return null
        }
        if (requestingPermission) return null
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
        try {
            try {
                activity.startActivityForResult(intent, PERMISSION_REQUEST)
            } catch (_: ActivityNotFoundException) {
                activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), PERMISSION_REQUEST)
            }
        } catch (_: ActivityNotFoundException) {
            return "overlay-unavailable"
        } catch (_: SecurityException) {
            return "overlay-unavailable"
        }
        requestingPermission = true
        return null
    }

    // Settings does not return a permission grant as an Activity result code. Only
    // a pending user request returning from this screen may enable the pet.
    fun result(requestCode: Int): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        if (requestingPermission) {
            requestingPermission = false
            val allowed = Settings.canDrawOverlays(activity)
            DesktopPets.setEnabled(activity, allowed)
            permissionError = if (allowed) null else "overlay-denied"
            publish()
        }
        return true
    }

    fun resumed() {
        if (Settings.canDrawOverlays(activity)) permissionError = null
        RuntimeForegroundService.refreshPetAfterResume()
        publish()
    }

    fun save(state: Bundle) { state.putBoolean(PERMISSION_PENDING, requestingPermission) }
    fun restore(state: Bundle?) { requestingPermission = state?.getBoolean(PERMISSION_PENDING) == true }

    fun closePreview() { answerPreview?.dismiss() }

    private companion object {
        const val PERMISSION_REQUEST = 0x5074
        const val PERMISSION_PENDING = "sugar-pet-permission-requested"
        const val MAX_SPEECH_PACKET = 32_768
    }
}

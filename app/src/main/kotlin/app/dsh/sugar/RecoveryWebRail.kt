package app.dsh.sugar

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import app.dsh.sugar.shared.WorkspaceEndpoint
import org.json.JSONObject
import kotlin.math.roundToInt

internal fun recoveryRailSettings(activity: Activity, side: String): JSONObject {
    val prefs = activity.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE)
    return JSONObject().put("language",SugarLanguage.read(activity)).put("side",side)
        .put("colorMode",prefs.getString("colorMode","adaptive")).put("accent",prefs.getString("accent","#AACAC0"))
        .put("blur",prefs.getBoolean("blur",true)).put("transparency",prefs.getInt("transparency",16))
        .put("reducedMotion",prefs.getBoolean("reducedMotion",false))
}

/** The recovery document gets a four-action rail and no production settings or runtime bridge. */
internal class RecoveryWebRail(
    private val activity: Activity, private val endpoint: WorkspaceEndpoint, restoredSide: String?,
    private val activeChanged: (Boolean) -> Unit, private val navigate: (String) -> Unit,
    private val turnActiveChanged: (Boolean) -> Unit = {}, private val editingInteraction: () -> Unit = {}
) {
    private val prefs = activity.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE)
    var side = restoredSide?.takeIf { it == "left" || it == "right" } ?: prefs.getString("side","right") ?: "right"
        private set
    private var generation = 0
    private var connection: WebMessagePort? = null
    private var active = false
    private var preview = false
    private var turnActive = false
    private var embedded: EmbeddedPreviewGesture? = null
    private var flags = 0
    private val source by lazy {
        listOf("sugar-i18n.js","sugar-edge-nav.js","sugar-page-motion.js","sugar-recovery-scrub.js","sugar-recovery-rail.js")
            .joinToString("\n;\n") { activity.assets.open(it).bufferedReader().use { reader -> reader.readText() } }
    }

    private fun trusted(view: WebView): Boolean {
        val url = view.url ?: return false
        val uri = Uri.parse(url)
        return endpoint.owns(url) && uri.path == "/" && !uri.queryParameterNames.contains("token")
    }

    fun attach(view: WebView) {
        if (!trusted(view)) return
        val id = ++generation
        val settings = recoveryRailSettings(activity,side)
            .put("home",activity.sugarString(R.string.diag_session_home)).put("forward",activity.sugarString(R.string.diag_forward))
            .put("end",activity.sugarString(R.string.diag_end))
        val origin = JSONObject.quote(endpoint.origin)
        val script = """(() => {
            if (window !== top || location.origin !== $origin || location.pathname !== '/' || new URL(location.href).searchParams.has('token') || !document.body) return false;
            $source
            return window.SugarRecoveryRail.mount($settings);
        })()"""
        view.evaluateJavascript(script) { installed ->
            if (installed != "true" || generation != id || !trusted(view)) return@evaluateJavascript
            val (host, page) = view.createWebMessageChannel()
            connection = host
            host.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(port: WebMessagePort, message: WebMessage) {
                    if (connection !== port || !trusted(view) || generation != id) return
                    val data = message.data ?: return
                    if (data.length > 256) return
                    when (data) {
                        "ready" -> {
                            if (active) return
                            active = true
                            activeChanged(true)
                            embedded = EmbeddedPreviewGesture(view,endpoint) { preview }.also { it.attach() }
                            host.postMessage(WebMessage(flags.toString()))
                        }
                        "editing" -> if (active && view.hasWindowFocus()) editingInteraction()
                        "end","home","back","forward" -> if (active && view.hasWindowFocus()) navigate(data)
                        else -> if (active) updateDock(view,data)
                    }
                }
            })
            view.postWebMessage(WebMessage("sugar-recovery/rail", arrayOf(page)),Uri.parse(endpoint.origin))
        }
    }

    private fun updateDock(view: WebView, data: String) {
        val layout = try { JSONObject(data) } catch (_: org.json.JSONException) { return }
        val anchor = layout.optString("side").takeIf { it == "left" || it == "right" } ?: return
        val width = layout.optDouble("width")
        val dock = layout.optDouble("dockWidth")
        if (!width.isFinite() || width < 1 || !dock.isFinite() || dock !in 0.0..width / 2) return
        setTurnActive(layout.optBoolean("turnActive"))
        side = anchor
        preview = layout.optBoolean("preview")
        val dockedLeft = layout.optString("dockSide") == "left"
        SugarDialog.updateDock(activity,dockedLeft,(dock * view.width / width).roundToInt())
    }

    private fun setTurnActive(value: Boolean) {
        if (turnActive == value) return
        turnActive = value
        turnActiveChanged(value)
    }

    fun setDocumentHistory(value: Int) {
        if (flags == value) return
        flags = value
        if (active) connection?.postMessage(WebMessage(flags.toString()))
    }

    fun back(edge: String?): Boolean {
        if (!active) return false
        val command = when (edge) { "left" -> "edge-left"; "right" -> "edge-right"; else -> "system-back" }
        connection?.postMessage(WebMessage(command))
        return true
    }

    fun detach() {
        generation++
        connection?.close()
        connection = null
        embedded?.detach()
        embedded = null
        preview = false
        setTurnActive(false)
        flags = 0
        if (active) { active = false; activeChanged(false) }
    }
}

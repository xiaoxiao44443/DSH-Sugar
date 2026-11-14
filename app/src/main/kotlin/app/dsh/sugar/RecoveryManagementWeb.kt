package app.dsh.sugar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Offline, application-owned recovery settings. Only the resources in the APK allowlist are reachable. */
internal class RecoveryManagementWeb(
    private val activity: Activity, private val labels: JSONObject, initialSide: String?,
    private val action: (String) -> Unit, private val fallback: () -> Unit
) {
    private val root = FrameLayout(activity)
    private val paper = activity.getColor(R.color.sugar_recovery_paper)
    private val prefs = activity.getSharedPreferences("edge-navigation", Activity.MODE_PRIVATE)
    val web = WebView(activity)
    private val images = WebUiAssets(activity)
    var side = initialSide ?: prefs.getString("side","right") ?: "right"
        private set
    private var port: WebMessagePort? = null
    private var insets = Insets.NONE
    private var busy = true
    private var message = ""
    private var loadingMode: String? = null
    private var ready = false
    private var disposed = false
    private val timeout = Runnable { if (!ready && !disposed) fallback() }

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        web.setBackgroundColor(paper)
        root.setBackgroundColor(paper)
        root.addView(web)
        activity.setContentView(root)
        WorkspaceChrome(activity, root, { paper }) { safe ->
            insets = safe
            if (ready) sendInsets()
        }
        activity.applyRecoveryGesturePreference()
        web.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val density = view.resources.displayMetrics.density
            val length = if (prefs.getBoolean("fullEdgeGestures",false)) view.height else minOf(view.height,(200 * density).roundToInt())
            val width = minOf((32 * density).roundToInt(), view.width / 2)
            val top = (view.height - length) / 2
            view.systemGestureExclusionRects = listOf(Rect(0,top,width,top+length),Rect(view.width-width,top,view.width,top+length))
        }
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url.toString()
                if (request.method == "GET" && url.startsWith("$ORIGIN/")) {
                    val name = url.removePrefix("$ORIGIN/")
                    if (name == "assets/sugar/launcher-icon.png") images.intercept(request,ORIGIN)?.let { return it }
                    val type = when (name) {
                        "sugar-recovery-management.html" -> "text/html"
                        "sugar-settings.css" -> "text/css"
                        "sugar-i18n.js","sugar-edge-nav.js","sugar-page-motion.js","sugar-recovery-page.js","sugar-recovery-management.js" -> "text/javascript"
                        else -> null
                    }
                    if (type != null) {
                        val stream = try { activity.assets.open(name) } catch (_: java.io.IOException) { null }
                        if (stream != null) {
                            val body = if (type == "text/html") stream.bufferedReader().use { reader ->
                                val surface = "#%06x".format(java.util.Locale.ROOT,paper and 0xffffff)
                                val scheme = if (nightMode()) "dark" else "light"
                                reader.readText().replace("<!--sugar-recovery-surface-->",
                                    "<style>html{--sugar-recovery-paper:$surface;color-scheme:$scheme}</style>").byteInputStream()
                            } else stream
                            return WebResourceResponse(type,"UTF-8",200,"OK", mapOf("Cache-Control" to "no-store"),body)
                        }
                    }
                }
                return WebResourceResponse("text/plain","UTF-8",404,"Not Found",emptyMap(),null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!disposed && url == PAGE && view.url == PAGE && port == null) connect()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!disposed) fallback()
                return true
            }
        }
        web.loadUrl(PAGE)
        root.requestApplyInsets()
        root.postDelayed(timeout,8000)
    }

    private fun nightMode() = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun connect() {
        val config = recoveryRailSettings(activity,side).put("labels",labels).put("dark",nightMode())
        web.evaluateJavascript("window.SugarRecoveryManagement?.mount($config) == true") { installed ->
            if (disposed || web.url != PAGE) return@evaluateJavascript
            if (installed != "true") { fallback(); return@evaluateJavascript }
            val (native,page) = web.createWebMessageChannel()
            port = native
            native.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(sender: WebMessagePort, packet: WebMessage) {
                    if (disposed || port !== sender || web.url != PAGE) return
                    val text = packet.data ?: return
                    if (text.length > 256) return
                    when (text) {
                        "ready" -> {
                            ready = true
                            root.removeCallbacks(timeout)
                            sendInsets()
                            sendState()
                        }
                        "home","back","current","builtin","restore","reset" -> if (ready && !busy && web.hasWindowFocus()) action(text)
                        else -> if (ready) dock(text)
                    }
                }
            })
            web.postWebMessage(WebMessage("sugar-recovery/management", arrayOf(page)), Uri.parse(ORIGIN))
        }
    }

    private fun dock(text: String) {
        val data = try { JSONObject(text) } catch (_: org.json.JSONException) { return }
        val next = data.optString("side").takeIf { it == "left" || it == "right" } ?: return
        val width = data.optDouble("width")
        val dock = data.optDouble("dockWidth")
        if (!width.isFinite() || width < 1 || !dock.isFinite() || dock !in 0.0..width/2) return
        side = next
        SugarDialog.updateDock(activity,data.optString("dockSide") == "left",(web.width * dock / width).roundToInt())
    }

    private fun sendInsets() {
        val sides = JSONArray(listOf(insets.left,insets.top,insets.right,insets.bottom))
        port?.postMessage(WebMessage(JSONObject().put("insets",sides).toString()))
    }

    fun update(isBusy: Boolean, text: String, mode: String?) {
        busy = isBusy
        message = text
        loadingMode = mode
        if (ready) sendState()
    }

    private fun sendState() {
        val state = JSONObject().put("busy",busy).put("message",message).put("loadingMode",loadingMode ?: JSONObject.NULL)
        port?.postMessage(WebMessage(state.toString()))
    }

    fun back(edge: String?) {
        if (!ready) {
            if (edge == null && !busy) action("back")
            return
        }
        port?.postMessage(WebMessage(when (edge) { "left" -> "edge-left"; "right" -> "edge-right"; else -> "system-back" }))
    }

    fun dispose() {
        disposed = true
        root.removeCallbacks(timeout)
        port?.close()
        port = null
        root.removeView(web)
        web.destroy()
    }

    companion object {
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val PAGE = "$ORIGIN/sugar-recovery-management.html"
    }
}

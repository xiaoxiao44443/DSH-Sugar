package app.dsh.sugar.workspace

import app.dsh.sugar.R
import app.dsh.sugar.sugarString

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.Toast
import app.dsh.sugar.runtime.SugarRuntime
import app.dsh.sugar.shared.WorkspaceEndpoint
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/** Android selects a tree. The authenticated DSH client creates the corresponding workspace ID. */
internal class LocalFolderPicker(private val activity: Activity) {
    private var browser: WebView? = null
    private var endpoint: WorkspaceEndpoint? = null
    private var page = ""
    private var choosing = false
    private var selected: LocalFolderWorkspaces.Selection? = null
    private var ticket: String? = null

    fun attach(view: WebView, host: WorkspaceEndpoint) {
        if (!host.owns(view.url.orEmpty())) return
        detach()
        browser = view
        endpoint = host
        page = UUID.randomUUID().toString()
        val base = JSONObject.quote("dsh-sugar://local-folder/$page/")
        view.evaluateJavascript("""
            (() => {
              if (window !== top || location.origin !== ${JSONObject.quote(host.origin)}) return;
              window.__dshSugarChooseLocal = () => {
                if (!navigator.userActivation?.isActive) return false;
                location.href = $base + 'choose';
                return true;
              };
              window.__dshSugarPrepareLocal = (ticket, slug, title, id) => {
                Promise.resolve().then(() => window.__dshSugarRegisterLocal(slug,title,id)).then(
                  project => { location.href = $base + 'result?' + new URLSearchParams({ticket,project}); },
                  () => { location.href = $base + 'result?' + new URLSearchParams({ticket}); });
              };
            })()
        """.trimIndent()) { if (browser === view) prepare() }
    }

    fun detach() { browser = null; endpoint = null; page = ""; ticket = null }
    fun restore(state: Bundle?) {
        choosing = state?.getBoolean("local.folder.choosing") == true
        val slug = state?.getString("local.folder.slug") ?: return
        selected = LocalFolderWorkspaces.Selection(slug, state.getString("local.folder.title").orEmpty(),
            state.getString("local.folder.id"))
    }
    fun save(state: Bundle) {
        state.putBoolean("local.folder.choosing", choosing)
        selected?.let {
            state.putString("local.folder.slug", it.slug); state.putString("local.folder.title", it.title)
            state.putString("local.folder.id", it.project)
        }
    }
    private fun feedback(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun prepare() {
        val selection = selected ?: return
        val view = browser ?: return
        if (ticket != null || endpoint?.owns(view.url.orEmpty()) != true) return
        val nonce = UUID.randomUUID().toString()
        ticket = nonce
        view.evaluateJavascript("window.__dshSugarPrepareLocal?.(" +
            listOf(nonce, selection.slug, selection.title, selection.project).joinToString(",") {
                it?.let(JSONObject::quote) ?: "null"
            } + ")", null)
    }

    fun handle(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme != "dsh-sugar" || url.host != "local-folder") return false
        val parts = url.pathSegments
        if (view !== browser || !request.isForMainFrame || endpoint?.owns(view.url.orEmpty()) != true ||
            parts.size != 2 || parts.first() != page) return true
        when (parts[1]) {
            "choose" -> if (request.hasGesture() && !choosing && ticket == null) {
                try {
                    choosing = true
                    activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQUEST)
                } catch (_: android.content.ActivityNotFoundException) {
                    choosing = false
                    feedback(activity.sugarString(R.string.ws_no_picker))
                }
            }
            "result" -> {
                val active = ticket ?: return true
                if (url.getQueryParameter("ticket") != active) return true
                ticket = null
                val selection = selected ?: return true
                selected = null
                val id = url.getQueryParameter("project").orEmpty()
                if (!runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
                    feedback(activity.sugarString(R.string.ws_registration_failed))
                    return true
                }
                val originalPage = page
                thread(name = "sugar-local-folder-binding") {
                    val result = runCatching {
                        val projects = checkNotNull(SugarRuntime.activeProjects)
                        val root = "/workspace/" + selection.slug
                        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                        while (!projects.hasWorkspace(id, root) && android.os.SystemClock.elapsedRealtime() < deadline) {
                            Thread.sleep(40)
                        }
                        check(projects.hasWorkspace(id, root)) { activity.sugarString(R.string.ws_registration_unconfirmed) }
                        LocalFolderWorkspaces.bind(activity, selection.slug, id)
                    }
                    activity.runOnUiThread {
                        if (browser === view && originalPage == page && !activity.isDestroyed) {
                            result.onSuccess {
                                view.evaluateJavascript("window.__dshSugarOpenLocal?.(" + JSONObject.quote(id) + ")", null)
                            }.onFailure { feedback(it.localizedMessage ?: activity.sugarString(R.string.ws_registration_unavailable)) }
                        }
                    }
                }
            }
        }
        return true
    }

    fun result(code: Int, result: Int, data: Intent?): Boolean {
        if (code != REQUEST) return false
        choosing = false
        val uri = data?.data ?: return true
        if (result != Activity.RESULT_OK) return true
        thread(name = "sugar-local-folder-grant") {
            val record = runCatching {
                check(LegacyFolderLinks.retire(activity)) { activity.sugarString(R.string.ws_links_retiring) }
                LocalFolderWorkspaces.select(activity, uri, data.flags)
            }
            activity.runOnUiThread {
                if (!activity.isDestroyed) record.onSuccess { selected = it; prepare() }
                    .onFailure { feedback(it.localizedMessage ?: activity.sugarString(R.string.ws_cannot_select)) }
            }
        }
        return true
    }

    companion object { private const val REQUEST = 7642 }
}

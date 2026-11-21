package app.dsh.sugar.workspace

import android.app.Activity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import app.dsh.sugar.shared.WorkspaceEndpoint
import org.json.JSONObject
import java.util.UUID

/** Gesture-bound file actions from the authenticated main frame. */
internal class WorkspaceMenus(private val activity: Activity) {
    private val emptyFolder = EmptyFolderDialog(activity)
    private val reveal = ProjectFolderReveal(activity)
    private var browser: WebView? = null
    private var bound: WorkspaceEndpoint? = null
    private var nonce = ""

    fun detach() {
        emptyFolder.close()
        bound = null
        browser = null
        nonce = ""
    }

    fun attach(view: WebView, endpoint: WorkspaceEndpoint) {
        if (!endpoint.owns(view.url.orEmpty())) return
        detach()
        browser = view
        bound = endpoint
        nonce = UUID.randomUUID().toString()
        val base = JSONObject.quote("dsh-sugar://workspace/$nonce/")
        view.evaluateJavascript("""
            (() => {
              if (window !== window.top || location.origin !== ${JSONObject.quote(endpoint.origin)}) return;
              window.__dshSugarRevealFolder = (id, path, kind = 'file') => {
                if (!navigator.userActivation?.isActive || typeof id !== 'string' || typeof path !== 'string' ||
                    (kind !== 'file' && kind !== 'directory')) return false;
                location.href = $base + 'revealFile?' + new URLSearchParams({id, path, kind});
                return true;
              };
              const pending = new Map();
              let serial = 0;
              window.__dshSugarFolderResult = (id, removed, error) => {
                const result = pending.get(id);
                if (!result) return;
                pending.delete(id);
                if (error) result.reject(new Error(error)); else result.resolve(removed);
              };
              const message = text => window.SugarI18n?.text(text) ?? text;
              window.__dshSugarRemoveEmptyFolder = path => new Promise((resolve, reject) => {
                if (!navigator.userActivation?.isActive) {
                  reject(new Error(message("请点击删除空文件夹按钮。"))); return;
                }
                if (pending.size) { reject(new Error(message("请先完成当前操作。"))); return; }
                const id = String(++serial);
                pending.set(id, {resolve, reject});
                location.href = $base + "removeEmpty?" + new URLSearchParams({id, path});
              });
            })()
        """.trimIndent(), null)
    }

    fun handle(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme != "dsh-sugar" || url.host != "workspace") return false
        val segments = url.pathSegments
        if (!request.isForMainFrame || !request.hasGesture() || browser !== view ||
            bound?.owns(view.url.orEmpty()) != true || segments.size != 2 || segments[0] != nonce ||
            url.toString().length > 48_000) return true
        val page = nonce
        fun current() = browser === view && nonce == page && bound?.owns(view.url.orEmpty()) == true &&
            !activity.isFinishing && !activity.isDestroyed
        when (segments[1]) {
            "revealFile" -> {
                val kind = url.getQueryParameter("kind") ?: "file"
                if (kind != "file" && kind != "directory") return true
                reveal.show(url.getQueryParameter("id"), url.getQueryParameter("path"), kind == "directory") {
                    current() && activity.hasWindowFocus()
                }
            }
            "removeEmpty" -> {
                val id = url.getQueryParameter("id") ?: return true
                val path = url.getQueryParameter("path") ?: return true
                if (!id.matches(Regex("[0-9]{1,12}"))) return true
                emptyFolder.show(path) { removed, error ->
                    if (current()) {
                        val feedback = error?.let(JSONObject::quote) ?: "null"
                        view.evaluateJavascript("window.__dshSugarFolderResult?.(${JSONObject.quote(id)}, $removed, $feedback)", null)
                    }
                }
            }
        }
        return true
    }
}

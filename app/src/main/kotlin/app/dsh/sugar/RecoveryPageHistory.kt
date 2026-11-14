package app.dsh.sugar

import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import app.dsh.sugar.shared.WorkspaceEndpoint
import org.json.JSONObject

/** Tracks the temporary DSH SPA and same-origin documents. The page can report two availability
 * bits through this port. It cannot invoke a native action or end its session. */
internal class RecoveryPageHistory(
    private val view: WebView, private val endpoint: WorkspaceEndpoint,
    private val changed: (Boolean, Boolean, Int) -> Unit
) {
    private var epoch = 0L
    private var port: WebMessagePort? = null
    private var appFlags = 0
    private val origin = JSONObject.quote(endpoint.origin)

    fun reset() {
        epoch++
        port?.close()
        port = null
        appFlags = 0
        changed(false,false,0)
    }

    fun connect() {
        if (port != null || !endpoint.owns(view.url.orEmpty())) return
        val generation = ++epoch
        val script = """(() => {
            if (window !== top || location.origin !== $origin) return false;
            function receive(e) {
                if (e.data !== 'sugar-recovery/history' || e.ports.length !== 1 || (e.source && e.source !== window)) return;
                removeEventListener('message',receive);
                const channel = e.ports[0];
                const publish = () => {
                    const s = window.SugarNavigation?.state?.();
                    channel.postMessage(String(s?.busy ? 4 : (s?.canBack ? 1 : 0) + (s?.canForward ? 2 : 0)));
                };
                addEventListener('sugar-navigation-state',publish);
                publish();
            }
            addEventListener('message',receive);
            return true;
        })()"""
        view.evaluateJavascript(script) { installed ->
            if (generation != epoch || installed != "true" || !endpoint.owns(view.url.orEmpty())) return@evaluateJavascript
            val (native,page) = view.createWebMessageChannel()
            port = native
            native.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(sender: WebMessagePort, message: WebMessage) {
                    if (port !== sender || !endpoint.owns(view.url.orEmpty())) return
                    appFlags = when (message.data) { "0" -> 0; "1" -> 1; "2" -> 2; "3" -> 3; "4" -> 4; else -> return }
                    update()
                }
            })
            view.postWebMessage(WebMessage("sugar-recovery/history",arrayOf(page)),Uri.parse(endpoint.origin))
        }
        update()
    }

    private fun safeTarget(url: String?): Boolean =
        url != null && endpoint.owns(url) && !Uri.parse(url).queryParameterNames.contains("token")

    private fun offset(step: Int): Int? {
        val list = view.copyBackForwardList()
        var index = list.currentIndex + step
        while (index in 0 until list.size) {
            val item = list.getItemAtIndex(index)
            if (safeTarget(item.url) && safeTarget(item.originalUrl)) return index - list.currentIndex
            index += step
        }
        return null
    }

    fun update() {
        val documents = (if (offset(-1) != null) 1 else 0) or (if (offset(1) != null) 2 else 0)
        val all = if (appFlags == 4) 0 else appFlags or documents
        changed(all and 1 != 0,all and 2 != 0,documents)
    }

    fun goDocument(command: String) {
        val step = when (command) { "back" -> -1; "forward" -> 1; else -> return }
        offset(step)?.let(view::goBackOrForward)
    }

    fun go(command: String) {
        if (command !in setOf("home","back","forward") || !endpoint.owns(view.url.orEmpty())) return
        val generation = epoch
        val name = JSONObject.quote(command)
        val js = """(() => {
            if (window !== top || location.origin !== $origin) return true;
            const nav = window.SugarNavigation, state = nav?.state?.();
            if (state?.busy || ($name === 'home' && state?.isHome)) return true;
            return nav?.action?.($name) === true;
        })()"""
        view.evaluateJavascript(js) { handled ->
            if (generation != epoch || handled == "true" || !endpoint.owns(view.url.orEmpty())) return@evaluateJavascript
            if (command == "home") view.loadUrl(endpoint.home)
            else goDocument(command)
        }
    }
}

package app.dsh.sugar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import app.dsh.sugar.shared.WorkspaceEndpoint
import java.net.URI

/** DSH first opens about:blank on the tap and later replaces it with its account authorization URL. */
internal class BrowserLogin(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var popup: WebView? = null
    private val expire = Runnable { close() }

    fun open(parent: WebView, message: Message, endpoint: WorkspaceEndpoint, trustedPage: () -> Boolean): Boolean {
        val transport = message.obj as? WebView.WebViewTransport ?: return false
        if (!trustedPage()) return false
        close()
        val link = parent.hitTestResult?.takeIf {
            it.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || it.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        }?.extra
        val child = WebView(activity)
        popup = child
        child.settings.apply {
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = true
        }
        fun navigate(url: String): Boolean {
            if (url == "about:blank") return false
            if (popup === child) {
                if (trustedPage()) {
                    val destination = Uri.parse(url)
                    if (isAuthorizationPage(url) || (url == link && destination.scheme == "https")) {
                        openBrowser(destination)
                    } else if (url == link && endpoint.owns(url)) {
                        parent.loadUrl(url)
                    }
                }
                close()
            }
            return true
        }
        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                !request.isForMainFrame || navigate(request.url.toString())

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                navigate(url)
            }
        }
        child.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) { if (popup === window) close() }
        }
        transport.webView = child
        message.sendToTarget()
        handler.postDelayed(expire, 120_000L)
        return true
    }

    fun close() {
        handler.removeCallbacks(expire)
        val child = popup ?: return
        popup = null
        handler.post { child.destroy() }
    }

    fun openSource() = openBrowser(Uri.parse("https://github.com/xiaoxiao44443/DSH-Sugar"))

    fun openLicenseSource(id: String) {
        OpenSourceNotices.sourceUrl(activity, id)?.let { openBrowser(Uri.parse(it)) }
    }

    fun openLicenseRelease() {
        OpenSourceNotices.releaseUrl(activity)?.let { openBrowser(Uri.parse(it)) }
    }

    @Suppress("DEPRECATION")
    private fun openBrowser(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = activity.packageManager
        val preferred = pm.resolveActivity(browsers, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val choices = pm.queryIntentActivities(browsers, PackageManager.MATCH_DEFAULT_ONLY)
        val target = if (preferred != null && choices.any { it.activityInfo.packageName == preferred.packageName }) {
            intent.setPackage(preferred.packageName)
        } else {
            // Let the system's browser picker resolve a domain-neutral URL while delivering the real data.
            intent.apply { selector = browsers }
        }
        try {
            activity.startActivity(target)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, activity.sugarString(R.string.sugar_file_browser_missing), Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(activity, activity.sugarString(R.string.sugar_file_browser_launch), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        fun isAuthorizationPage(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && uri.host.equals("platform.deepseek.com", ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null &&
                uri.rawPath == "/dsh/authorize" && uri.rawFragment == null
        } catch (_: java.net.URISyntaxException) { false }
    }
}

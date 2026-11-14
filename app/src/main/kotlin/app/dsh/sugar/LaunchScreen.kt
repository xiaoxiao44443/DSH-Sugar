package app.dsh.sugar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.dsh.sugar.shared.LaunchState
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/** A local animated cover above the real workspace. Backend startup runs independently. */
internal class LaunchScreen(
    private val activity: Activity,
    private val retry: () -> Unit,
    private val onOpened: () -> Unit,
) : FrameLayout(activity) {
    private var settings = SplashSettings.read(context)
    val paper get() = Color.parseColor(settings.backgroundColor)
    private val ink get() = if (Color.luminance(paper) > .179f) Color.BLACK else Color.WHITE
    private var safe = Insets.NONE
    private val fallback = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
    private var failureView: LaunchFailureView? = null
    private var foregroundActive = true
    private var document: WebView? = null
    private var painted = false
    private var ended = true
    private var ending = false
    private var ready = false
    private var failed = false
    private var message = ""
    private var percent: Int? = null
    private var awaitingFirstFrame = true
    private var nativeOnly = false
    private val imageTimeout = Runnable { if (!painted && !ended) useNativeCover() }
    private val deadline = Runnable { exitCover() }
    private val readyExit = Runnable { if (ready && !ended && !ending) exitCover() }
    private var paintTicket = 0L
    private val guard = Runnable { finishExit() }

    init {
        isClickable = true
        fallback.addView(label, LinearLayout.LayoutParams(-1, -2))
        fallback.addView(progress, LinearLayout.LayoutParams(dp(100), dp(4)).apply { topMargin = dp(12) })
        addView(fallback, LayoutParams(-1, -1))
    }

    fun begin() {
        dispose()
        ++paintTicket
        settings = SplashSettings.read(context)
        ended = false
        ready = false
        failed = false
        awaitingFirstFrame = true
        nativeOnly = false
        message = context.sugarString(R.string.sugar_launching)
        percent = null
        visibility = VISIBLE
        fallback.setBackgroundColor(paper)
        label.setTextColor(ink)
        progress.indeterminateTintList = ColorStateList.valueOf(ink)
        progress.progressTintList = progress.indeterminateTintList
        renderFallback()
        // Let the Activity dispatch the runtime launch before initializing Chromium.
        post { if (!ended && !ending && document == null && !activity.isDestroyed) createDocument() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!ended && awaitingFirstFrame && (painted || nativeOnly)) {
            awaitingFirstFrame = false
            if (settings.durationMs > 0) postDelayed(deadline, settings.durationMs.toLong())
            else if (ready) postOnAnimation(readyExit)
        }
    }

    fun updateInsets(insets: Insets) {
        safe = insets
        fallback.setPadding(dp(16) + safe.left, safe.top, dp(16) + safe.right, safe.bottom)
        failureView?.setPadding(dp(16) + safe.left, safe.top, dp(16) + safe.right, safe.bottom)
        if (painted) applyInsets()
    }

    private fun applyInsets() {
        val px = listOf(safe.top, safe.right, safe.bottom, safe.left).joinToString(" ") {
            "${it / resources.displayMetrics.density}px"
        }
        document?.evaluateJavascript("page.style.setProperty('--safe',${JSONObject.quote(px)})", null)
    }

    fun update(state: LaunchState) {
        val alreadyFailed = failed
        ready = state is LaunchState.Ready
        failed = state is LaunchState.Failed
        message = when (state) {
            is LaunchState.Starting -> state.message.ifBlank { context.sugarString(R.string.sugar_launching) }
            is LaunchState.Failed -> state.message.ifBlank { context.sugarString(R.string.sugar_start_error) }
            is LaunchState.Opening -> context.sugarString(R.string.sugar_loading_page)
            is LaunchState.Ready -> context.sugarString(R.string.splash_ready)
        }
        percent = if (ready) 100 else STAGE_PERCENT.find(message)?.groupValues?.get(1)?.toInt()?.coerceIn(0, 100)
        if (failed) {
            if (!alreadyFailed) dispose()
            visibility = VISIBLE
            val panel = failureView ?: LaunchFailureView(activity, retry, paper, ink).also {
                failureView = it
                it.setPadding(dp(16) + safe.left, safe.top, dp(16) + safe.right, safe.bottom)
                it.setForegroundActive(foregroundActive)
                addView(it, LayoutParams(-1,-1))
            }
            panel.describe(message)
        }
        renderFallback()
        if (painted && !ending) updateStage()
        if (ready && ended) openWorkspace()
        else if (ready && settings.durationMs == 0) {
            if (!awaitingFirstFrame || !animateExit()) exitCover()
            // A warm workspace may commit before the artwork. Give it a bounded
            // chance to draw; the first real frame schedules an earlier exit.
            else if (handler?.hasCallbacks(readyExit) != true) postDelayed(readyExit, 700)
        }
    }

    private fun renderFallback() {
        fallback.visibility = if (failed || (painted && !ending) || (ready && (ending || ended))) GONE else VISIBLE
        label.text = if (ended || ending || failed) message else settings.loadingText
        label.visibility = if (label.text.isNotEmpty()) VISIBLE else GONE
        progress.isIndeterminate = percent == null
        percent?.let { progress.progress = it }
        progress.visibility = if (!failed && (ended || ending || settings.showProgress)) VISIBLE else GONE
    }

    private fun updateStage() {
        document?.evaluateJavascript("updateStage(${JSONObject.quote(message)},$percent)", null)
    }

    private fun openWorkspace() {
        if (visibility == GONE) return
        visibility = GONE
        onOpened()
    }

    private fun animateExit() = settings.randomExit && !reducedMotion()

    fun whenWorkspacePainted(view: WebView, present: () -> Unit) {
        val ticket = ++paintTicket
        if (visibility != VISIBLE || !animateExit()) { present(); return }
        // onPageCommitVisible can represent an empty HTML shell. Wait for actual
        // workspace content and its compositor submission before revealing it.
        fun current() = ticket == paintTicket && view.isAttachedToWindow && !activity.isDestroyed
        val finish = Runnable { if (current()) { ++paintTicket; present() } }
        fun probe() {
            if (!current()) return
            view.evaluateJavascript("performance.getEntriesByName('first-contentful-paint','paint').length > 0") { ink ->
                if (!current()) return@evaluateJavascript
                if (ink == "true") {
                    view.postVisualStateCallback(ticket, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            removeCallbacks(finish)
                            finish.run()
                        }
                    })
                } else postDelayed(::probe, 64)
            }
        }
        postDelayed(finish, 1200) // Paint Timing may be suppressed on some WebViews.
        probe()
    }

    private fun exitCover() {
        if (ended || ending) return
        ending = true
        removeCallbacks(deadline)
        renderFallback()
        if (painted && animateExit()) {
            document?.evaluateJavascript("exitSplash()", null)
            postDelayed(guard, 650) // A backgrounded renderer may stop delivering animation callbacks.
        } else if (!painted && ready && animateExit()) {
            // If artwork couldn't be rendered, dissolve the native loading layer.
            fallback.visibility = VISIBLE
            fallback.animate().alpha(0f).setDuration(230).withEndAction { finishExit() }.start()
        } else finishExit()
    }

    private fun finishExit() {
        if (!ending) return
        dispose()
        renderFallback()
        if (ready) openWorkspace()
    }

    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
        failureView?.setForegroundActive(active)
    }

    fun dispose() {
        failureView?.let(::removeView)
        failureView = null
        ended = true
        ending = false
        painted = false
        removeCallbacks(deadline)
        removeCallbacks(guard)
        removeCallbacks(readyExit)
        removeCallbacks(imageTimeout)
        fallback.animate().cancel()
        fallback.alpha = 1f
        document?.let { document = null; removeView(it); it.destroy() }
    }

    private fun useNativeCover() {
        if (ending) { finishExit(); return }
        removeCallbacks(imageTimeout)
        document?.let { document = null; removeView(it); it.destroy() }
        painted = false
        nativeOnly = true
        renderFallback()
        invalidate()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createDocument() {
        val cover = try { WebView(activity) } catch (_: RuntimeException) { useNativeCover(); return }
        document = cover
        postDelayed(imageTimeout, 6_000)
        cover.isSaveEnabled = false
        cover.setBackgroundColor(Color.TRANSPARENT)
        cover.alpha = 0f
        cover.settings.apply {
            javaScriptEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = true
        }
        val image = SplashSettings.customImage(context)
        val config = settings.json().put("language", SugarLanguage.read(context)).put("custom", image != null).put("reducedMotion", reducedMotion())
        cover.addJavascriptInterface(object {
            @JavascriptInterface fun painted() { post {
                if (document === cover && !ended && !ending) {
                    applyInsets()
                    updateStage()
                    // An image load event can precede its first composited frame, especially for APNG.
                    cover.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (document !== cover || ended || ending) return
                            removeCallbacks(imageTimeout)
                            painted = true
                            cover.alpha = 1f
                            renderFallback()
                            invalidate()
                        }
                    })
                }
            } }
            @JavascriptInterface fun dismissed() { post { if (document === cover) finishExit() } }
        }, "SugarLaunch")
        // All resources, including custom media, are served as typed streams at fixed URLs.
        // The document has no access to the DSH origin, file://, content://, or the network.
        cover.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                fun local(mime: String, bytes: InputStream) = WebResourceResponse(mime, null, 200, "OK",
                    mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), bytes)
                try {
                    when (request.url.toString()) {
                        "$ORIGIN/index.html" -> return local("text/html", resources.assets.open("sugar-splash/index.html"))
                        "$ORIGIN/layout.js" -> return local("application/javascript", resources.assets.open("sugar-splash-layout.js"))
                        "$ORIGIN/motion.js" -> return local("application/javascript", resources.assets.open("sugar-page-motion.js"))
                        "$ORIGIN/default.png" -> return local("image/png", resources.assets.open("sugar-splash/default.png"))
                        "$ORIGIN/image" -> if (image != null) {
                            SplashSettings.imageMime(image)?.let { return local(it, image.inputStream()) }
                        }
                    }
                } catch (_: IOException) { /* A replaced or unreadable image falls back in the page. */ }
                return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (document === view && url == "$ORIGIN/index.html") view.evaluateJavascript("configure($config)", null)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (document === view) useNativeCover()
                return true
            }
        }
        addView(cover, LayoutParams(-1, -1))
        cover.loadUrl("$ORIGIN/index.html")
    }

    private fun reducedMotion() = !android.animation.ValueAnimator.areAnimatorsEnabled() ||
        context.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE).getBoolean("reducedMotion", false)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private companion object {
        const val ORIGIN = "https://splash.invalid"
        val STAGE_PERCENT = Regex("""(\d{1,3})\s*%""")
    }
}

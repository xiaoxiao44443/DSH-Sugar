package app.dsh.sugar

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Build
import android.app.AlertDialog
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.TextView
import app.dsh.sugar.shared.WorkspaceEndpoint
import app.dsh.sugar.runtime.RecoverySession
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** This private process has its own WebView profile and a recovery-only navigation channel. */
class RecoveryBrowserActivity : Activity() {
    private var web: WebView? = null
    private var history: RecoveryPageHistory? = null
    private lateinit var endpoint: WorkspaceEndpoint
    private lateinit var page: FrameLayout
    private lateinit var problem: TextView
    private lateinit var navigation: RecoveryNavigation
    private lateinit var webRail: RecoveryWebRail
    private val taskScreen by lazy { TaskScreenDimmer(this) }
    private var turnActive = false
    private val railAssets by lazy { WebUiAssets(this) }
    private val profileLink by lazy { SettingsDocumentEditor(this) }
    private lateinit var chrome: WorkspaceChrome
    private lateinit var login: String
    private var authenticated = false
    private var firstDocument = true
    private var confirm: AlertDialog? = null
    private val systemEdge = SystemBackEdge(this)
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var host: IBinder? = null
    private val ended = IBinder.DeathRecipient { runOnUiThread { finish() } }
    private var viewerToken = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val url = intent.getStringExtra(URL).orEmpty()
        val uri = Uri.parse(url)
        val origin = WorkspaceEndpoint.parse("http://127.0.0.1:${uri.port}")
        viewerToken = intent.getStringExtra(TOKEN).orEmpty()
        val owner = intent.extras?.getBinder(OWNER)
        if (Application.getProcessName() != "$packageName:recovery" || !valid.matches(viewerToken) ||
            origin == null || !origin.owns(url) || owner == null) { finish(); return }
        try { owner.linkToDeath(ended, 0); host = owner } catch (_: android.os.RemoteException) { finish(); return }
        // Main receives this process's death even if the renderer crashes before it can return a result.
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<android.os.ResultReceiver>(LIFETIME)?.send(0, Bundle().apply { putBinder(OWNER, Binder()) })
        endpoint = origin
        login = url
        authenticated = state?.getBoolean("authenticated") == true
        page = FrameLayout(this)
        problem = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(24,24,24,24)
            textSize = 16f
            text = sugarString(R.string.diag_browser_problem)
            setTextColor(getColor(R.color.sugar_ink))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        page.addView(problem)
        navigation = RecoveryNavigation(this,page,null,::navigate,session = true)
        webRail = RecoveryWebRail(this,endpoint,state?.getString("railSide"),
            { active -> navigation.showWebRail(active,webRail.side) },::navigate,
            turnActiveChanged = { active ->
                turnActive = active
                taskScreen.taskChanged(active)
            }, editingInteraction = { taskScreen.interacted() })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.sugar_paper))
            addView(navigation)
        }
        setContentView(root)
        chrome = WorkspaceChrome(this,root,{ getColor(R.color.sugar_paper) }) { safe ->
            navigation.setSessionInsets(safe)
            val gap = (24 * resources.displayMetrics.density).toInt()
            problem.setPadding(safe.left + gap,safe.top + gap,safe.right + gap,safe.bottom + gap)
        }
        navigation.onSessionDockChanged = chrome::reserveDock
        applyRecoveryGesturePreference()
        root.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = systemEdge.callback(::back).also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,it)
            }
        }
        openBrowser()
        if (state?.getBoolean("confirmEnd") == true) confirmEnd()
    }

    private fun openBrowser() {
        try {
            if (profile == null) {
                if (lease == null) lease = acquireProfileLock(this)
                if (lease == null) { showProblem(); return }
                WebView.setDataDirectorySuffix(suffix(viewerToken))
                profile = viewerToken
            }
            if (profile != viewerToken) { showProblem(); return }
            display()
        } catch (_: RuntimeException) { showProblem() }
    }

    private fun showProblem() {
        webRail.detach()
        chrome.detach()
        web?.visibility = View.INVISIBLE
        problem.visibility = View.VISIBLE
        if (web == null) navigation.setHistory(false,false)
    }

    private fun confirmEnd() {
        if (confirm?.isShowing == true || isFinishing) return
        confirm = SugarDialog.builder(this,440)
            .setTitle(sugarString(R.string.diag_end_question))
            .setMessage(sugarString(R.string.diag_end_detail))
            .setNegativeButton(sugarString(R.string.diag_cancel),null)
            .setPositiveButton(sugarString(R.string.diag_end)) { _,_ -> setResult(RESULT_OK); finish() }
            .setOnDismissListener { confirm = null }.show()
    }

    private fun navigate(command: String) {
        if (command == "end") { confirmEnd(); return }
        if (command == "home" && problem.visibility == View.VISIBLE) {
            val view = web
            if (view == null) openBrowser() else view.loadUrl(if (authenticated) endpoint.home else login)
        } else if (problem.visibility == View.VISIBLE) history?.goDocument(command)
        else history?.go(command)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        systemEdge.observe(event)
        taskScreen.touch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        taskScreen.interacted()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        taskScreen.focusChanged(hasFocus)
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) taskScreen.resume(turnActive)
    }

    override fun onPause() {
        taskScreen.pause()
        super.onPause()
    }

    @Deprecated("Platform back dispatch")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() = back(systemEdge.take())

    private fun back(side: String?) {
        if (::navigation.isInitialized && !webRail.back(side)) navigation.back(side)
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putBoolean("authenticated",authenticated)
        if (::webRail.isInitialized) state.putString("railSide",webRail.side)
        state.putBoolean("confirmEnd",confirm?.isShowing == true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun display() {
        val browser = WebView(this)
        web = browser
        firstDocument = true
        val routes = RecoveryPageHistory(browser,endpoint) { back,forward,documents ->
            navigation.setHistory(back,forward)
            webRail.setDocumentHistory(documents)
        }
        history = routes
        browser.isSaveEnabled = false
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser,false)
        // A webpage may close its own transient windows, never the native diagnostic session.
        browser.webChromeClient = object : WebChromeClient() { override fun onCloseWindow(window: WebView) {} }
        browser.webViewClient = object : WebViewClient() {
            private var failed = false
            override fun onPageStarted(view: WebView, address: String, favicon: android.graphics.Bitmap?) {
                webRail.detach()
                chrome.detach()
                routes.reset()
                failed = !endpoint.owns(address)
                if (failed) { view.stopLoading(); showProblem() }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "dsh-sugar") {
                    val address = view.url?.let(Uri::parse)
                    if (authenticated && web === view && !failed && view.hasWindowFocus() &&
                        address?.path == "/" && !address.queryParameterNames.contains("token")) {
                        profileLink.handle(view,request) {
                            val key = RecoveryProfileEditorActivity.PROJECT
                            intent.getStringExtra(key)?.let { id ->
                                startActivity(Intent(this@RecoveryBrowserActivity,RecoveryProfileEditorActivity::class.java).putExtra(key,id))
                            }
                        }
                    }
                    return true
                }
                return !endpoint.owns(request.url.toString())
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.toString() == endpoint.home + "assets/sugar/launcher-icon.png") return railAssets.intercept(request,endpoint.origin)
                if (endpoint.owns(request.url.toString())) return null
                return WebResourceResponse("text/plain","utf-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(byteArrayOf()))
            }
            override fun onPageCommitVisible(view: WebView, address: String) {
                if (web !== view || failed || !endpoint.owns(address) || Uri.parse(address).queryParameterNames.contains("token")) return
                authenticated = true
                if (firstDocument) { view.clearHistory(); firstDocument = false }
                problem.visibility = View.GONE
                view.visibility = View.VISIBLE
                chrome.attach(view,endpoint.origin)
                routes.connect()
                webRail.attach(view)
                if (Uri.parse(address).path == "/") profileLink.attach(view,endpoint.origin,stockRecoveryButton = true)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (web === view) routes.update()
            }
            private fun failure(view: WebView, request: WebResourceRequest) {
                if (web !== view || !request.isForMainFrame) return
                failed = true
                showProblem()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) =
                failure(view,request)
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) =
                failure(view,request)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                webRail.detach()
                routes.reset()
                page.removeView(view)
                if (web === view) { web = null; history = null }
                view.destroy()
                showProblem()
                return true
            }
        }
        page.addView(browser,0,FrameLayout.LayoutParams(-1,-1))
        browser.loadUrl(if (authenticated) endpoint.home else login)
    }

    override fun onDestroy() {
        if (::webRail.isInitialized) webRail.detach()
        if (::chrome.isInitialized) chrome.detach()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        confirm?.dismiss()
        history?.reset()
        host?.unlinkToDeath(ended,0)
        web?.let { browser ->
            (browser.parent as? android.view.ViewGroup)?.removeView(browser)
            browser.stopLoading()
            if (isFinishing) {
                browser.clearHistory()
                browser.clearCache(true)
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
            }
            browser.destroy()
        }
        super.onDestroy()
        if (isFinishing && Application.getProcessName() == "$packageName:recovery") {
            // Release Chromium's locks before the owner deletes this session's entire dedicated profile.
            Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
        }
    }

    companion object {
        private const val URL = "url"
        private const val TOKEN = "session"
        private const val OWNER = "owner"
        private const val LIFETIME = "lifetime"
        private val valid = Regex("[a-f0-9]{32}")
        private val cleanupOwners = java.util.concurrent.ConcurrentHashMap<String, IBinder>()
        private var profile: String? = null
        // Keep the descriptor open until the viewer process ends and Chromium has released its writers.
        private var lease: RandomAccessFile? = null
        private fun suffix(token: String) = "sugar_recovery_$token"
        private fun acquireProfileLock(context: Context): RandomAccessFile? {
            val file = RandomAccessFile(File(context.noBackupFilesDir,"sugar-recovery-web.lock"),"rw")
            try {
                if (file.channel.tryLock() != null) return file
            } catch (_: OverlappingFileLockException) {
                // A cleanup thread in this process already owns the lock.
            } catch (error: Throwable) {
                file.close(); throw error
            }
            file.close()
            return null
        }

        fun intent(activity: Activity, url: String): Intent {
            val token = UUID.randomUUID().toString().replace("-","")
            val app = activity.applicationContext
            val death = IBinder.DeathRecipient {
                cleanupOwners.remove(token)
                Thread({ withInactiveProfiles(app) { eraseProfile(app,token) } }, "SugarRecoveryCleanup").start()
            }
            val lifetime = object : android.os.ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val child = resultData?.getBinder(OWNER) ?: return
                    cleanupOwners[token] = child
                    try { child.linkToDeath(death,0) } catch (_: android.os.RemoteException) { death.binderDied() }
                }
            }
            return Intent(activity, RecoveryBrowserActivity::class.java).putExtra(URL,url).putExtra(TOKEN,token)
                .putExtra(RecoveryProfileEditorActivity.PROJECT,requireNotNull(RecoverySession.profileProject))
                .putExtras(Bundle().apply { putBinder(OWNER,Binder()); putParcelable(LIFETIME,lifetime) })
        }

        /** A previously force-stopped pair of processes could not receive the normal binder-death cleanup. */
        fun removeAbandonedProfiles(context: Context) {
            val tokens = profileRoots(context).flatMap { (dir,prefix) ->
                dir.list().orEmpty().mapNotNull { name ->
                    name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf(valid::matches)
                }
            }.toSet()
            if (tokens.isNotEmpty()) withInactiveProfiles(context) {
                tokens.forEach { eraseProfile(context,it) }
            }
        }
        @Synchronized private fun withInactiveProfiles(context: Context, action: () -> Unit) {
            try { acquireProfileLock(context)?.use { action() } }
            catch (_: java.io.IOException) { /* An inaccessible profile can be retried on normal startup. */ }
        }
        private fun profileRoots(context: Context) = listOf(
            context.dataDir to "app_webview_sugar_recovery_", context.cacheDir to "webview_sugar_recovery_",
            context.noBackupFilesDir to ".webview_sugar_recovery_")

        private fun eraseProfile(context: Context, token: String) {
            require(valid.matches(token))
            for ((root,prefix) in profileRoots(context)) {
                val dir = File(root,prefix + token)
                try {
                    // walkFileTree does not follow symlinks. Only explicitly named recovery profiles are eligible.
                    Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                        }
                        override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                            if (error != null) throw error
                            Files.deleteIfExists(directory); return FileVisitResult.CONTINUE
                        }
                    })
                } catch (_: java.io.IOException) { /* Retry abandoned recovery profiles before the next launch. */ }
            }
        }
    }
}

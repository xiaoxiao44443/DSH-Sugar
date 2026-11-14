package app.dsh.sugar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.dsh.sugar.runtime.RuntimeForegroundService
import app.dsh.sugar.shared.LaunchState
import app.dsh.sugar.shared.WorkspaceEndpoint
import app.dsh.sugar.shared.WorkspaceSession
import app.dsh.sugar.workspace.LegacyFolderLinks
import app.dsh.sugar.workspace.ProjectFolderReveal
import app.dsh.sugar.workspace.WorkspaceMenus

class WorkspaceActivity : Activity() {
    private val session = WorkspaceSession()
    private lateinit var root: FrameLayout
    private lateinit var splash: LaunchScreen
    private var web: WebView? = null
    private lateinit var workspaceChrome: WorkspaceChrome
    private lateinit var webAssets: WebUiAssets
    private val taskScreen by lazy { TaskScreenDimmer(this) }
    private fun hasAgentTurn() = DesktopPetActivity.status().sessionId != null
    private val turnChanged: () -> Unit = { runOnUiThread { taskScreen.taskChanged(hasAgentTurn()) } }
    private val edgeNavigation: EdgeNavigationHost by lazy {
        EdgeNavigationHost(this, ::updateBack, ::recoveryAction, ::leaveRecovery, editingInteraction = { taskScreen.interacted() })
    }
    private var recovery: RecoveryController? = null
    private var recoveryExit: String? = null
    private var resumed = false
    private val uploads = UploadPicker()
    private val localFolders by lazy { app.dsh.sugar.workspace.LocalFolderPicker(this) }
    private val artifactTransfer by lazy { ArtifactTransfer(this) }
    private val browserLogin by lazy { BrowserLogin(this) }
    private val settingsEditor by lazy { SettingsDocumentEditor(this) }
    private val workspaceMenus by lazy { WorkspaceMenus(this) }
    @Volatile private var activeEndpoint: WorkspaceEndpoint? = null
    private var runtimeListener: RuntimeForegroundService.Listener? = null
    private var launchAttempt = 0L
    private var notificationSession: String? = null
    private val pendingMicrophones = mutableSetOf<PermissionRequest>()
    private var microphonePromptOpen = false
    private var historyCallback: OnBackInvokedCallback? = null
    private val systemEdge = SystemBackEdge(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationSession = if (savedInstanceState == null) AgentNotifications.target(intent)
            else savedInstanceState.getString(NOTIFICATION_SESSION)
        uploads.restore(savedInstanceState)
        edgeNavigation.petSettings.restore(savedInstanceState)
        edgeNavigation.pendingRecoveryRoute = savedInstanceState?.getString("recoveryRoute")
        localFolders.restore(savedInstanceState)
        val app = applicationContext
        Thread({ LegacyFolderLinks.retire(app) }, "sugar-retire-legacy-folders").start()
        webAssets = WebUiAssets(applicationContext)
        microphonePromptOpen = savedInstanceState?.getBoolean(MICROPHONE_PROMPT_OPEN) == true
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.sugar_paper))
        }
        splash = LaunchScreen(this, ::startSession, ::workspaceUncovered)
        root.addView(splash)
        setContentView(root)
        workspaceChrome = WorkspaceChrome(this, root, splash)
        root.requestApplyInsets()
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            webAssets.prepare()
        }, "sugar-web-assets").start()
        val recoveryKey = savedInstanceState?.getString("settingsRecovery")
        if (recoveryKey == null) startSession() else {
            // The old settings document belonged to the stopped DSH origin. Wait for its
            // viewer/cleanup before connecting a new WebView, including after process death.
            val retained = (lastNonConfigurationInstance as? RecoveryController)?.takeIf { it.key == recoveryKey }
                ?: RecoveryController.retained(recoveryKey)
            splash.begin()
            launchAttempt = session.begin()
            recoveryExit = savedInstanceState?.getString("settingsRecoveryExit") ?: "recovery"
            val controller = retained ?: RecoveryController(this)
            attachRecovery(controller)
            if (retained == null) controller.cleanup(sugarString(R.string.diag_interrupted)) else recoveryChanged()
        }
        settingsEditor.restoreReview()
    }

    private fun attachRecovery(controller: RecoveryController) {
        recovery = controller
        controller.attach(this, ::recoveryChanged)
        if (resumed) controller.resume(this)
    }

    private fun recoveryAction(action: String) {
        if (recoveryExit != null || !resumed) return
        val controller = recovery ?: RecoveryController(this).also(::attachRecovery)
        controller.action(action)
    }

    private fun leaveRecovery(route: String) {
        val controller = recovery ?: return
        controller.dismissConfirmation()
        if (!controller.touched) return
        recoveryExit = route
        // A committed WebView navigation may arrive after startActivityForResult hands off.
        // The visible diagnostic session now owns its lifetime until its explicit End result.
        if (!controller.viewer) controller.requestExit()
        recoveryChanged()
    }

    private fun recoveryChanged() {
        val controller = recovery ?: return
        if (isFinishing || isDestroyed) return
        if (controller.touched) {
            // Keep the local shell mounted while the maintenance gate stops the normal runtime.
            runtimeListener?.let(RuntimeForegroundService::detach)
            runtimeListener = null
        }
        edgeNavigation.reportRecovery(controller)
        if (web == null && controller.message.isNotEmpty()) {
            session.progress(launchAttempt, controller.message)
            render()
        }
        val route = recoveryExit ?: return
        if (!controller.busy && !controller.viewer) root.post {
            if (recovery !== controller || isFinishing || isDestroyed) return@post
            controller.detach(this)
            recovery = null
            recoveryExit = null
            edgeNavigation.pendingRecoveryRoute = route
            startSession()
        }
    }

    /** A recreated/detached document cannot render recovery status. Its viewer still owns the session. */
    private fun lostRecoveryPage(): Boolean {
        if (recovery?.touched != true) return false
        if (recoveryExit == null) recoveryExit = "recovery"
        if (session.state is LaunchState.Ready) {
            launchAttempt = session.begin()
            splash.begin()
            workspaceChrome.splashIcons()
            render()
        }
        recoveryChanged()
        return true
    }

    override fun onStart() {
        super.onStart()
        splash.setForegroundActive(true)
        AgentNotifications.activityStarted()
    }

    override fun onStop() {
        splash.setForegroundActive(false)
        AgentNotifications.activityStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AgentNotifications.target(intent)?.let {
            notificationSession = it
            openNotificationSession()
        }
    }

    private fun openNotificationSession() {
        if (recovery?.touched == true || RecoveryController.working) return
        val browser = web ?: return
        val endpoint = (session.state as? LaunchState.Ready)?.endpoint ?: return
        if (splash.visibility != View.GONE || !endpoint.owns(browser.url.orEmpty())) return
        NotificationEntryActivity.takeDeferred(this)?.let { notificationSession = it }
        val target = notificationSession ?: return
        edgeNavigation.pendingRecoveryRoute = null
        val origin = org.json.JSONObject.quote(endpoint.origin)
        val id = org.json.JSONObject.quote(target)
        browser.evaluateJavascript("""(() => {
            if (location.origin !== $origin || location.pathname !== '/') return false;
            window.__sugarNotificationSession = $id;
            window.SugarNavigation?.openNotification();
            return true;
        })()""") { sent ->
            if (sent == "true" && web === browser && activeEndpoint === endpoint && notificationSession == target)
                notificationSession = null
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (Build.VERSION.SDK_INT < 33 || microphonePromptOpen ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("runtime-notification", MODE_PRIVATE)
        if (prefs.getBoolean("requested", false)) return
        prefs.edit().putBoolean("requested", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        edgeNavigation.cancelEmbeddedGesture()
        root.requestApplyInsets()
    }

    private fun startSession() {
        discardBrowser()
        val id = session.begin()
        launchAttempt = id
        splash.begin()
        workspaceChrome.splashIcons()
        render()
        runtimeListener?.let(RuntimeForegroundService::detach)
        val listener = object : RuntimeForegroundService.Listener {
            override fun onRestarting() {
                if (!isFinishing && !isDestroyed) startSession()
            }

            override fun onProgress(message: String) {
                session.progress(id, message)
                render()
            }

            override fun onConnecting() {
                if (isFinishing || isDestroyed || launchAttempt != id || session.state !is LaunchState.Starting) return
                session.progress(id, this@WorkspaceActivity.sugarString(R.string.splash_connecting))
                render()
                if (web == null) createBrowser(id)
            }

            override fun onReady(address: String) {
                if (isFinishing || isDestroyed) return
                val endpoint = session.connected(id, address) ?: return
                activeEndpoint = endpoint
                val browser = web ?: createBrowser(id) ?: return
                render()
                browser.loadUrl(endpoint.home)
            }

            override fun onFailed(message: String) {
                session.failed(id, message)
                render()
            }
        }
        runtimeListener = listener
        RuntimeForegroundService.start(this, listener)
    }

    private fun render() {
        splash.update(session.state)
        web?.importantForAccessibility = if (splash.visibility == View.GONE) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (session.state is LaunchState.Failed) workspaceChrome.splashIcons()
    }

    private fun workspaceUncovered() {
        val view = web ?: return
        val endpoint = (session.state as? LaunchState.Ready)?.endpoint ?: return
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        workspaceChrome.attach(view, endpoint.origin)
        edgeNavigation.attach(view, endpoint)
        openNotificationSession()
        updateBack()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser(id: Long): WebView? {
        val browser = try { WebView(this) } catch (_: RuntimeException) {
            session.failed(id, this@WorkspaceActivity.sugarString(R.string.splash_no_webview))
            render()
            return null
        }
        web = browser
        browser.isSaveEnabled = false
        browser.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                                        resultMsg: android.os.Message): Boolean {
                if (!isUserGesture) return false
                val endpoint = (session.state as? LaunchState.Ready)?.endpoint ?: return false
                return browserLogin.open(view, resultMsg, endpoint) {
                    !isDestroyed && !isFinishing && web === view && activeEndpoint === endpoint && endpoint.owns(view.url.orEmpty())
                }
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                                           params: FileChooserParams): Boolean {
                val endpoint = (session.state as? LaunchState.Ready)?.endpoint
                if (web !== view || endpoint == null || !endpoint.owns(view.url.orEmpty())) {
                    callback.onReceiveValue(null)
                    return true
                }
                return uploads.open(this@WorkspaceActivity, callback, params)
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                if (web !== browser || !canGrantMicrophone(request)) {
                    request.deny()
                    return
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    return
                }
                pendingMicrophones.add(request)
                if (!microphonePromptOpen) {
                    microphonePromptOpen = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                pendingMicrophones.remove(request)
            }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                edgeNavigation.interceptSplash(view, request, activeEndpoint?.origin)
                    ?: webAssets.intercept(request, activeEndpoint?.origin)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (web === view) {
                    localFolders.detach()
                    workspaceMenus.detach()
                    artifactTransfer.detach()
                    uploads.cancel()
                    browserLogin.close()
                    finishMicrophoneRequests(granted = false)
                    edgeNavigation.detach()
                    workspaceChrome.detach()
                    lostRecoveryPage()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (web === view && session.state is LaunchState.Ready &&
                    (localFolders.handle(view, request) || workspaceMenus.handle(view, request) || settingsEditor.handle(view, request))) return true
                if (request.isForMainFrame && request.url.host == SplashPreviewAssets.HOST) return true
                if (!request.isForMainFrame || activeEndpoint?.owns(request.url.toString()) == true) return false
                if (web === view && session.state is LaunchState.Ready && request.hasGesture() && request.url.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: ActivityNotFoundException) { }
                }
                return true
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                val endpoint = activeEndpoint ?: return
                if (web !== view || !endpoint.owns(url)) return
                splash.whenWorkspacePainted(view) {
                    if (web !== view || !endpoint.owns(view.url.orEmpty()) || activeEndpoint !== endpoint) return@whenWorkspacePainted
                    session.pageVisible(id, url)
                    if (session.state is LaunchState.Ready) {
                        if (splash.visibility == View.GONE) workspaceUncovered() else render()
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { updateBack() }

            override fun onPageFinished(view: WebView, url: String) {
                val endpoint = activeEndpoint ?: return
                if (web === view && endpoint.owns(url)) {
                    if (splash.visibility == View.GONE) edgeNavigation.attach(view, endpoint)
                    settingsEditor.attach(view, endpoint.origin)
                    workspaceMenus.attach(view, endpoint)
                    localFolders.attach(view, endpoint)
                    artifactTransfer.attach(view, endpoint.origin)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && web === view && activeEndpoint?.owns(request.url.toString()) == true && !lostRecoveryPage()) {
                    session.failed(id, sugarString(R.string.sugar_connection_error))
                    render()
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && web === view && activeEndpoint?.owns(request.url.toString()) == true && !lostRecoveryPage()) {
                    session.failed(id, sugarString(R.string.sugar_page_error, response.statusCode))
                    render()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (web === view) {
                    discardBrowser()
                    if (!lostRecoveryPage()) {
                        session.failed(id, sugarString(R.string.sugar_connection_error))
                        render()
                    }
                }
                return true
            }
        }
        root.addView(browser, 0, FrameLayout.LayoutParams(-1, -1))
        return browser
    }

    private fun canGrantMicrophone(request: PermissionRequest): Boolean {
        val endpoint = (session.state as? LaunchState.Ready)?.endpoint ?: return false
        val page = web?.url ?: return false
        val origin = request.origin.toString()
        return !isFinishing && !isDestroyed && endpoint.owns(page) &&
            (origin == endpoint.origin || origin == endpoint.home) &&
            PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
    }

    private fun finishMicrophoneRequests(granted: Boolean) {
        val requests = pendingMicrophones.toList()
        pendingMicrophones.clear()
        for (request in requests) {
            if (granted && canGrantMicrophone(request)) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                request.deny()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        edgeNavigation.adbSettings.permissionResult(requestCode, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            RuntimeForegroundService.refreshNotification()
        }
        if (requestCode == REQUEST_MICROPHONE) {
            microphonePromptOpen = false
            finishMicrophoneRequests(permissions.singleOrNull() == Manifest.permission.RECORD_AUDIO &&
                grantResults.singleOrNull() == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        settingsEditor.retainReview()
        uploads.save(outState)
        edgeNavigation.petSettings.save(outState)
        outState.putString("recoveryRoute", edgeNavigation.pendingRecoveryRoute)
        recovery?.takeIf { it.touched }?.let { outState.putString("settingsRecovery", it.key) }
        outState.putString("settingsRecoveryExit", recoveryExit)
        localFolders.save(outState)
        outState.putBoolean(MICROPHONE_PROMPT_OPEN, microphonePromptOpen)
        outState.putString(NOTIFICATION_SESSION, notificationSession)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Platform Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (recovery?.viewerResult(requestCode, resultCode) == true) return
        if (requestCode == DiagnosticsRecoveryActivity.REQUEST) {
            if (resultCode == RESULT_OK) {
                val route = data?.getStringExtra(DiagnosticsRecoveryActivity.DESTINATION)
                val reconnect = data?.getBooleanExtra(DiagnosticsRecoveryActivity.RECONNECT, true) ?: true
                if (reconnect || route != null && route != "back" && session.state !is LaunchState.Ready) startSession()
                if (route != null && (route == "home" || route in RecoveryNavigation.settingsIcons)) {
                    edgeNavigation.navigateAfterRecovery(route)
                }
            }
            return
        }
        if (edgeNavigation.petSettings.result(requestCode)) return
        if (edgeNavigation.splashResult(requestCode, resultCode, data)) return
        if (localFolders.result(requestCode, resultCode, data) || requestCode == ProjectFolderReveal.BROWSE_PICKER || uploads.result(this, requestCode, resultCode, data) ||
            settingsEditor.onActivityResult(requestCode)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        systemEdge.observe(event)
        taskScreen.touch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        taskScreen.interacted()
    }

    private fun systemBack(edge: String?) {
        if (edge != null && edgeNavigation.consumeSystemEdge(edge)) return
        edgeNavigation.back(::browserBackOrExit)
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        if ((edgeNavigation.handlesSystemEdges || web?.canGoBack() == true) && session.state is LaunchState.Ready) {
            if (historyCallback == null) {
                val callback = systemEdge.callback(::systemBack)
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                historyCallback = callback
            }
        } else {
            historyCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            historyCallback = null
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Used by Android 10–12")
    override fun onBackPressed() {
        if ((edgeNavigation.canHandleBack || systemEdge.hasCancelledTouch) && session.state is LaunchState.Ready) systemBack(systemEdge.take())
        else if (web?.canGoBack() == true && session.state is LaunchState.Ready) web?.goBack()
        else super.onBackPressed()
    }

    private fun browserBackOrExit() {
        if (web?.canGoBack() == true && session.state is LaunchState.Ready) web?.goBack()
        else if (!isTaskRoot || !moveTaskToBack(false)) finishAfterTransition()
    }

    private fun discardBrowser() {
        edgeNavigation.detach()
        localFolders.detach()
        artifactTransfer.detach()
        workspaceMenus.detach()
        activeEndpoint = null
        uploads.cancel()
        browserLogin.close()
        workspaceChrome.detach()
        val old = web ?: return
        web = null
        finishMicrophoneRequests(granted = false)
        updateBack()
        root.removeView(old)
        old.destroy()
    }

    private companion object {
        const val REQUEST_MICROPHONE = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val NOTIFICATION_SESSION = "notificationSession"
        const val MICROPHONE_PROMPT_OPEN = "microphonePromptOpen"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        taskScreen.focusChanged(hasFocus)
        if (!hasFocus && web != null) edgeNavigation.cancelEmbeddedGesture()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        DesktopPetActivity.addListener(turnChanged)
        taskScreen.resume(hasAgentTurn())
        recovery?.resume(this)
        edgeNavigation.foreground(true)
        edgeNavigation.petSettings.resumed()
        if (web != null) edgeNavigation.adbSettings.resumed()
    }

    override fun onPause() {
        resumed = false
        DesktopPetActivity.removeListener(turnChanged)
        taskScreen.pause()
        recovery?.pause(this)
        edgeNavigation.foreground(false)
        edgeNavigation.cancelEmbeddedGesture()
        super.onPause()
    }

    override fun onRetainNonConfigurationInstance(): Any? = recovery

    override fun onDestroy() {
        recovery?.let {
            it.detach(this)
            if (isFinishing) it.requestExit()
        }
        runtimeListener?.let(RuntimeForegroundService::detach)
        splash.dispose()
        discardBrowser()
        super.onDestroy()
    }
}

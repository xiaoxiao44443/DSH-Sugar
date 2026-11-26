package app.dsh.sugar

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import app.dsh.sugar.shared.WorkspaceEndpoint
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale
import kotlin.math.roundToInt

/** App settings live outside the ephemeral DSH origin. Only its top-level UI gets this channel. */
internal class EdgeNavigationHost(private val activity: Activity, private val historyChanged: () -> Unit,
    private val recoveryAction: (String) -> Unit, private val leaveRecovery: (String) -> Unit,
    private val editingInteraction: () -> Unit = {}) {
    private val prefs = activity.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE)
    private val notificationPrefs = AgentNotificationPreferences(activity)
    private val splashPreview = SplashPreviewAssets(activity)
    private val splashPicker = SplashImagePicker(activity, ::reportSplash)
    private val splashChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "imageFile") reportSplash(null)
    }
    private val script by lazy {
        listOf("sugar-i18n.js", "sugar-edge-nav.js", "sugar-page-motion.js", "sugar-pane-deck.js", "sugar-splash-preview.js", "sugar-splash-settings.js", "sugar-mobile-controls.js", "sugar-pets.js", "sugar-storage.js", "sugar-workspace-files.js", "sugar-session-storage.js", "sugar-recovery-page.js", "sugar-task-mirror.js", "sugar-task-glass.js", "sugar-shell.js").joinToString("\n;\n") { name ->
            activity.assets.open(name).bufferedReader().use { it.readText() }
        }
    }
    private val sheet by lazy {
        JSONObject.quote(listOf("sugar-settings.css", "sugar-workspace-files.css", "sugar-pets.css").joinToString("\n") { name ->
            activity.assets.open(name).bufferedReader().use { it.readText() }
        })
    }
    private val firstAccent by lazy { listOf("#88BEB7", "#AC9ECD", "#DEA8A9", "#E2BF86", "#91AFD9", "#ACBD93").random() }
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var browser: WebView? = null
    private var channel: WebMessagePort? = null
    private val mobileUi = SugarMobileUi(activity) { page ->
        browser?.evaluateJavascript("window.SugarMobile?.setup(" + JSONObject.quote(page) + ")", null)
    }
    val adbSettings = AdbSettingsBridge(activity) { state -> channel?.postMessage(WebMessage(state.toString())) }
    private val storageUsage = StorageUsage(activity.applicationContext)
    private val sessionStorage = SessionStorageSettings(activity)
    private val workspaceFiles = WorkspaceFilesSettings(activity)
    private var workspaceFilesVisible = false
    private var sessionStorageVisible = false
    private var storageVisible = false
    private var petVisible = false
    val petSettings = PetSettingsBridge(activity) { payload ->
        if (petVisible) channel?.postMessage(WebMessage(payload.toString()))
    }
    private var settingsVisible = false
    private var connectionSettingsVisible = false
    private var licenseSettingsVisible = false
    private var recoveryVisible = false
    private var embeddedPanelOpen = false
    private var embeddedGesture: EmbeddedPreviewGesture? = null
    private var expanded = false
    val handlesSystemEdges: Boolean get() = channel != null
    private var fullEdges = false
    private var previousBarBehavior = 0
    private var lastBounds: JSONObject? = null
    private var lastColor = ""
    private var lastSample = 0L
    private var sampleVersion = 0L
    private data class Backdrop(val area: Rect, val width: Int, val height: Int, val left: Boolean, val version: Long)
    private var pendingSample: Backdrop? = null
    private val sample = Runnable { sampleBackdrop() }
    var canHandleBack = false
        private set
    private var directoryPickerOpen = false

    private fun notificationsEnabled() = activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName)))
        }
    }

    private val appInfo by lazy { AboutApp.read(activity) }

    private fun settings(previewUrl: String) = JSONObject()
        .put("appInfo", appInfo)
        .put("recoveryLabels", RecoveryController.labels(activity))
        .put("language", SugarLanguage.read(activity))
        .put("notifications", notificationPrefs.read())
        .put("systemNotificationsEnabled", notificationsEnabled())
        .put("splashPreviewUrl", previewUrl)
        .put("splash", SplashSettings.read(activity).json())
        .put("side", prefs.getString("side", "right"))
        .put("colorMode", prefs.getString("colorMode", "adaptive"))
        .put("accent", prefs.getString("accent", firstAccent))
        .put("blur", prefs.getBoolean("blur", true))
        .put("transparency", prefs.getInt("transparency", 16))
        .put("reducedMotion", prefs.getBoolean("reducedMotion", false))
        .put("fullEdgeGestures", prefs.getBoolean("fullEdgeGestures", false))
        .put("dimScreenDuringTasks", prefs.getBoolean(TaskScreenDimmer.PREFERENCE, true))

    fun attach(view: WebView, endpoint: WorkspaceEndpoint) {
        if (browser === view || !endpoint.owns(view.url.orEmpty()) || Uri.parse(view.url).path !in listOf("", "/")) return
        browser = view
        val epoch = ++generation
        val origin = JSONObject.quote(endpoint.origin)
        val settings = settings(splashPreview.attach(view, endpoint.origin))
        val js = """(() => {
            if (window !== top || location.origin !== $origin || location.pathname !== '/') return false;
            $script
            window.SugarShell.mount($settings, $sheet);
            return true;
        })()"""
        view.evaluateJavascript(js) { installed ->
            if (epoch != generation || !endpoint.owns(view.url.orEmpty())) return@evaluateJavascript
            if (installed != "true") { detach(); return@evaluateJavascript }
            val (host, page) = view.createWebMessageChannel()
            channel = host
            (view.parent as? android.widget.FrameLayout)?.let { container ->
                mobileUi.attach(container) { conversation, done ->
                    if (channel !== host || !endpoint.owns(view.url.orEmpty())) done(false)
                    else {
                        val target = JSONObject.quote(conversation)
                        view.evaluateJavascript("location.origin === $origin && window.SugarNavigation?.openConversation?.($target) === true") {
                            done(it == "true" && channel === host && endpoint.owns(view.url.orEmpty()))
                        }
                    }
                }
            }
            adbSettings.attach()
            useFullEdges(prefs.getBoolean("fullEdgeGestures", false))
            embeddedGesture = EmbeddedPreviewGesture(view, endpoint) { embeddedPanelOpen }.also { it.attach() }
            historyChanged()
            host.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(port: WebMessagePort, message: WebMessage) {
                    if (channel !== host || !endpoint.owns(view.url.orEmpty())) return
                    val raw = message.data ?: return
                    if (raw.length > 262144) return
                    val data = try { JSONObject(raw) } catch (_: org.json.JSONException) { return }
                    if (raw.length > 2048 && data.optString("type") !in setOf("petSpeech", "state",
                        "workspaceFilesQuery", "workspaceFilesDelete", "workspaceFilesOpen")) return
                    when (data.optString("type")) {
                        "editingInteraction" -> if (view.isShown && view.hasWindowFocus()) editingInteraction()
                        "mobileConversation", "mobileScreenTitles", "mobileSettings", "mobilePreference", "mobileAccessibility", "mobilePreview" ->
                            mobileUi.handle(data, view.hasWindowFocus()) { answer ->
                                if (channel === host && endpoint.owns(view.url.orEmpty())) host.postMessage(WebMessage(answer.toString()))
                            }
                        "adbStatus", "adbPairNotification", "adbAutoConnect", "adbPair", "adbConnect",
                        "adbDisconnect", "openWirelessSettings" -> adbSettings.handle(data, view.hasWindowFocus())
                        "rootStatus", "rootSetEnabled", "rootProbe" -> adbSettings.handle(data,
                            connectionSettingsVisible && view.isShown && view.hasWindowFocus())
                        "setLanguage" -> {
                            val tag = data.optString("language")
                            if (SugarLanguage.set(activity, tag)) {
                                host.postMessage(WebMessage(JSONObject().put("type", "languageState").put("language", tag)
                                    .put("recoveryLabels", RecoveryController.labels(activity)).toString()))
                                reportSplash(null)
                                adbSettings.resumedLanguage()
                                AdbPairingNotice.languageChanged(activity)
                                AgentNotifications.refreshChannelNames(activity)
                                app.dsh.sugar.runtime.RuntimeForegroundService.languageChanged()
                            }
                        }
                        "storageQuery" -> if (storageVisible) storageUsage.query(data) { result ->
                            if (channel === host && storageVisible && endpoint.owns(view.url.orEmpty())) {
                                host.postMessage(WebMessage(result.toString()))
                            }
                        }
                        "browseInternalFiles" -> if (activity is WorkspaceActivity && storageVisible && view.hasWindowFocus()) {
                            app.dsh.sugar.workspace.ProjectFolderReveal(activity).showInternal()
                        }
                        "sessionStorageQuery", "sessionStorageDelete", "sessionStorageOpen" -> if (activity is WorkspaceActivity && sessionStorageVisible) {
                            sessionStorage.handle(data, view.isShown && view.hasWindowFocus()) { result ->
                                if (channel === host && sessionStorageVisible && endpoint.owns(view.url.orEmpty())) {
                                    host.postMessage(WebMessage(result.toString()))
                                }
                            }
                        }
                        "workspaceFilesQuery", "workspaceFilesDelete", "workspaceFilesOpen" -> if (activity is WorkspaceActivity && workspaceFilesVisible) {
                            workspaceFiles.handle(data, view.isShown && view.hasWindowFocus()) { result ->
                                if (channel === host && workspaceFilesVisible && endpoint.owns(view.url.orEmpty())) {
                                    host.postMessage(WebMessage(result.toString()))
                                }
                            }
                        }
                        "recoveryAction" -> if (settingsVisible && recoveryVisible && view.hasWindowFocus()) {
                            recoveryAction(data.optString("action"))
                        }
                        "settings" -> save(data)
                        "popupPalette" -> {
                            val surface = data.optString("surface")
                            val ink = data.optString("ink")
                            val opacity = data.optDouble("opacity")
                            val blur = data.optDouble("blurDp")
                            if (HEX.matches(surface) && HEX.matches(ink) && opacity in 0.0..1.0 && blur in 0.0..32.0) {
                                val pageSurface = data.optString("pageSurface").takeIf(HEX::matches) ?: surface
                                val pageInk = data.optString("pageInk").takeIf(HEX::matches) ?: ink
                                SugarDialog.updatePalette(activity, pageSurface, pageInk, opacity, blur)
                                SugarOverlayPalette.update(activity, pageSurface, pageInk, opacity, blur)
                                mobileUi.palette(pageSurface, pageInk, opacity)
                            }
                        }
                        "notificationSettings" -> data.optJSONObject("changes")?.let { notificationPrefs.update(it) }
                        "notificationStatus" -> host.postMessage(WebMessage(JSONObject()
                            .put("type", "notificationStatus").put("enabled", notificationsEnabled()).toString()))
                        "openNotificationSettings" -> if (view.hasWindowFocus()) openNotificationSettings()
                        "openSource" -> if (settingsVisible && view.isShown && view.hasWindowFocus()) BrowserLogin(activity).openSource()
                        "openLicenseSource" -> if (licenseSettingsVisible && view.isShown && view.hasWindowFocus())
                            BrowserLogin(activity).openLicenseSource(data.optString("id"))
                        "openLicenseRelease" -> if (licenseSettingsVisible && view.isShown && view.hasWindowFocus())
                            BrowserLogin(activity).openLicenseRelease()
                        "petStatus", "petChange", "petSpeech", "petAnswerPreview", "petVoiceStatus" -> if (petVisible)
                            petSettings.handle(data, view.hasWindowFocus())
                        "petVoiceSettings" -> if (petVisible && view.isShown && view.hasWindowFocus()) {
                            view.evaluateJavascript("location.origin === $origin && location.pathname === '/' && " +
                                "window.SugarNavigation?.openPetVoiceSettings?.() === true") { opened ->
                                if (opened != "true" && channel === host && petVisible && endpoint.owns(view.url.orEmpty())) {
                                    host.postMessage(WebMessage(JSONObject().put("type", "petVoiceNavigation")
                                        .put("opened", false).toString()))
                                }
                            }
                        }
                        "splashSettings" -> {
                            val changes = data.optJSONObject("changes")
                            val valid = changes != null && SplashSettings.update(activity, changes)
                            reportSplash(if (valid) null else activity.sugarString(R.string.splash_invalid))
                        }
                        "splashPick" -> splashPicker.open()
                        "splashResetImage" -> splashPicker.reset()
                        "state" -> {
                            val previous = canHandleBack
                            val wasExpanded = expanded
                            canHandleBack = data.optBoolean("back")
                            directoryPickerOpen = data.optBoolean("picker")
                            expanded = data.optBoolean("expanded")
                            val nowSettings = data.optBoolean("settings")
                            val showStorage = nowSettings && data.optBoolean("storage")
                            // A gesture between the list and its detail keeps the active request until the route settles.
                            val showSessions = nowSettings && data.optBoolean("sessionStorage", sessionStorageVisible)
                            if (sessionStorageVisible && !showSessions) sessionStorage.close()
                            sessionStorageVisible = showSessions
                            if (data.has("sessionStorageDetail")) sessionStorage.detailChanged(data.optString("sessionStorageDetail"))
                            if (!nowSettings || data.has("workspaceFiles")) {
                                workspaceFilesVisible = nowSettings && data.optBoolean("workspaceFiles")
                                if (workspaceFilesVisible)
                                    workspaceFiles.page(data.optString("workspaceFilesProject"), data.optString("workspaceFilesPath"))
                                else workspaceFiles.page("", "")
                            }
                            if (storageVisible && !showStorage) storageUsage.cancel()
                            storageVisible = showStorage
                            petVisible = nowSettings && data.optBoolean("pet")
                            if (!petVisible) petSettings.closePreview()
                            if (settingsVisible != nowSettings || wasExpanded != expanded) invalidateSample()
                            settingsVisible = nowSettings
                            mobileUi.settingsVisible(nowSettings)
                            embeddedPanelOpen = !settingsVisible && data.optBoolean("embedded")
                            if (previous != canHandleBack || wasExpanded != expanded) historyChanged()
                            // A scrub can stage the neighbour and then cancel. Only act on a settled route.
                            val route = data.optString("settingsRoute")
                            connectionSettingsVisible = nowSettings && route == "adb"
                            licenseSettingsVisible = nowSettings && (route == "licenses" || route == "license-detail")
                            if (route.isNotEmpty() && route.length <= 40) {
                                val previousRecovery = recoveryVisible
                                recoveryVisible = nowSettings && route == "recovery"
                                if (previousRecovery && !recoveryVisible) leaveRecovery(route)
                            }
                        }
                        "taskRail" -> updateTaskRail(view, data)
                        "bounds" -> {
                            lastBounds = data
                            updateHandle(view, data)
                            val side = data.optString("dockSide")
                            val viewportWidth = data.optDouble("width")
                            val dockWidth = data.optDouble("dockWidth", 0.0)
                            val width = if (side in setOf("left", "right") && viewportWidth.isFinite() &&
                                viewportWidth > 0 && dockWidth in 0.0..(viewportWidth / 2))
                                (view.width * (dockWidth / viewportWidth)).roundToInt() else 0
                            SugarDialog.updateDock(activity, side == "left", width)
                        }
                        "sample" -> requestSample(view, data)
                        "invalidate" -> invalidateSample()
                    }
                }
            })
            view.postWebMessage(WebMessage("dsh-sugar/edge-shell", arrayOf(page)), Uri.parse(endpoint.origin))
            deliverRecoveryRoute()
            // A picker import started by a previous Activity may have finished while this page mounted.
            SplashSettings.preferences(activity).registerOnSharedPreferenceChangeListener(splashChanges)
            reportSplash(null)
        }
    }

    fun interceptSplash(view: WebView, request: android.webkit.WebResourceRequest, origin: String?) =
        splashPreview.intercept(view, request, origin)

    fun splashResult(requestCode: Int, resultCode: Int, data: Intent?) =
        splashPicker.result(requestCode, resultCode, data)

    private fun reportSplash(error: String?) {
        val reply = JSONObject().put("type", "splashState").put("splash", SplashSettings.read(activity).json())
        if (error != null) reply.put("error", error)
        channel?.postMessage(WebMessage(reply.toString()))
    }

    private fun rectangle(coords: JSONArray?): RectF? {
        if (coords == null || coords.length() != 4) return null
        val parts = (0..3).map { coords.optDouble(it).toFloat() }
        if (parts.any { !it.isFinite() || kotlin.math.abs(it) > 32768f }) return null
        return RectF(parts[0], parts[1], parts[2], parts[3]).takeUnless { it.isEmpty }
    }

    private fun rails(data: JSONObject): List<RectF> {
        val boxes = data.optJSONArray("rails") ?: return emptyList()
        return (0 until minOf(2, boxes.length())).mapNotNull { rectangle(boxes.optJSONArray(it)) }
    }

    fun cancelEmbeddedGesture() { embeddedGesture?.cancel() }
    fun foreground(visible: Boolean) = mobileUi.foreground(visible)

    private fun save(data: JSONObject) {
        val side = data.optString("side")
        val mode = data.optString("colorMode")
        val accent = data.optString("accent")
        val transparency = data.optInt("transparency", -1)
        if (side !in listOf("left", "right") || mode !in listOf("adaptive", "random") ||
            !HEX.matches(accent) || transparency !in 0..100 || data.opt("blur") !is Boolean || data.opt("reducedMotion") !is Boolean) return
        if (side != prefs.getString("side", "right")) invalidateSample()
        val full = data.optBoolean("fullEdgeGestures", prefs.getBoolean("fullEdgeGestures", false))
        val dimDuringTasks = data.optBoolean("dimScreenDuringTasks", prefs.getBoolean(TaskScreenDimmer.PREFERENCE, true))
        prefs.edit().putBoolean("fullEdgeGestures", full).putString("side", side).putString("colorMode", mode).putString("accent", accent)
            .putBoolean(TaskScreenDimmer.PREFERENCE, dimDuringTasks)
            .putInt("transparency", transparency)
            .putBoolean("blur", data.getBoolean("blur")).putBoolean("reducedMotion", data.getBoolean("reducedMotion")).apply()
        useFullEdges(full)
    }

    @Suppress("DEPRECATION")
    private fun useFullEdges(enable: Boolean) {
        if (fullEdges == enable) return
        val decor = activity.window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = activity.window.insetsController ?: return
            if (enable) {
                previousBarBehavior = controller.systemBarsBehavior
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.navigationBars())
            } else {
                controller.systemBarsBehavior = previousBarBehavior
                controller.show(WindowInsets.Type.navigationBars())
            }
        } else {
            val flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            val current = decor.systemUiVisibility
            if (enable) previousBarBehavior = current and flags
            decor.systemUiVisibility = if (enable) current or flags else (current and flags.inv()) or previousBarBehavior
        }
        fullEdges = enable
        val view = browser ?: return
        lastBounds?.let { updateHandle(view, it) }
        decor.requestApplyInsets()
    }

    private fun updateTaskRail(view: WebView, data: JSONObject) {
        val viewport = data.optDouble("width")
        if (!viewport.isFinite() || viewport < 1 || view.width <= 0) return
        val scale = view.width / viewport
        val origin = IntArray(2)
        view.getLocationInWindow(origin)
        fun regions(key: String): List<TaskRailRegion> {
            val items = data.optJSONArray(key) ?: return emptyList()
            return (0 until items.length().coerceAtMost(3)).mapNotNull { i ->
                val item = items.optJSONArray(i) ?: return@mapNotNull null
                val bounds = FloatArray(5) { (item.optDouble(it) * scale).toFloat() }
                val (left, top, right, bottom, radius) = bounds
                if (!bounds.all(Float::isFinite) || right <= left || bottom <= top || radius < 0) return@mapNotNull null
                TaskRailRegion(origin[0] + left, origin[1] + top, origin[0] + right, origin[1] + bottom, radius)
            }
        }
        mobileUi.updateNavigation(regions("visible"), regions("touch"))
    }

    private fun updateHandle(view: WebView, data: JSONObject) {
        val width = data.optDouble("width")
        val zones = data.optJSONArray("zones")
        if (!width.isFinite() || width < 1 || zones == null || zones.length() > 2) {
            view.systemGestureExclusionRects = emptyList()
            return
        }
        val density = view.resources.displayMetrics.density
        val scale = view.width / width
        val strip = (36 * density).roundToInt().coerceAtMost(view.width / 2)
        val rects = mutableListOf<Rect>()
        for (i in 0 until zones.length()) {
            val zone = zones.optJSONArray(i) ?: continue
            val coordinates = (0..3).map { zone.optDouble(it) * scale }
            if (coordinates.any { !it.isFinite() }) continue
            val (left, top, right, bottom) = coordinates.map { it.roundToInt() }
            if (bottom <= top || right <= left || view.width <= 0 || view.height <= 0) continue
            // With a visible navigation bar Android budgets up to 200 dp vertically per side.
            // Sticky immersive navigation can exclude the full side until system bars are revealed.
            val height = if (fullEdges) view.height else minOf(view.height, (200 * density).roundToInt())
            val y = ((top + bottom - height) / 2).coerceIn(0, view.height - height)
            val rect = if (left + right < view.width) Rect(0, y, strip, y + height)
                else Rect(view.width - strip, y, view.width, y + height)
            if (!rect.isEmpty) rects.add(rect)
        }
        if (view.systemGestureExclusionRects != rects) view.systemGestureExclusionRects = rects
    }

    private fun invalidateSample() {
        sampleVersion++
        pendingSample = null
        browser?.removeCallbacks(sample)
        lastColor = ""
    }

    @Suppress("DEPRECATION")
    private fun requestSample(view: WebView, data: JSONObject) {
        if (settingsVisible || !view.hasWindowFocus() || !view.isShown) return
        val cssWidth = data.optDouble("width")
        if (!cssWidth.isFinite() || cssWidth < 1) return
        val factor = view.width / cssWidth
        val left = prefs.getString("side", "right") == "left"
        val rail = rails(data).firstOrNull { (it.centerX() < cssWidth / 2) == left } ?: return
        val windowInsets = view.rootWindowInsets ?: return
        val insets = if (Build.VERSION.SDK_INT >= 30)
            windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        else windowInsets.stableInsets
        val density = view.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        val position = IntArray(2)
        view.getLocationInWindow(position)
        val window = activity.window.decorView
        val safeLeft = maxOf(0, insets.left - position[0])
        val safeRight = minOf(view.width, window.width - insets.right - position[0])
        val safeTop = maxOf(0, insets.top - position[1])
        val safeBottom = minOf(view.height, window.height - insets.bottom - position[1])
        // Use the visible page alongside the whole rail. Both the rail and its shadow
        // lie outside the strip. The WebView's own bounds already exclude the keyboard.
        val boundary = ((if (left) rail.right else rail.left) * factor).roundToInt()
        val start = maxOf(safeLeft, if (left) boundary + px(10) else boundary - px(54))
        val end = minOf(safeRight, if (left) boundary + px(54) else boundary - px(10))
        val area = Rect(start, safeTop, end, safeBottom)
        if (area.isEmpty) return
        val scheduled = pendingSample != null
        pendingSample = Backdrop(area, view.width, view.height, left, ++sampleVersion)
        // New events replace the pending position. Idle pages never schedule another copy.
        if (!scheduled) view.postDelayed(sample, maxOf(0L, lastSample + 140L - SystemClock.uptimeMillis()))
    }

    private fun sampleBackdrop() {
        val request = pendingSample ?: return
        pendingSample = null
        val view = browser ?: return
        if (!view.isShown || !view.hasWindowFocus() || view.width != request.width || view.height != request.height ||
            settingsVisible || activity.isFinishing || activity.isDestroyed) return
        val epoch = generation
        val position = IntArray(2)
        view.getLocationInWindow(position)
        val area = Rect(request.area).apply { offset(position[0], position[1]) }
        val columns = SidebarBackdropColor.COLUMNS
        val rows = SidebarBackdropColor.ROWS
        val pixels = Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888)
        lastSample = SystemClock.uptimeMillis()
        try {
            PixelCopy.request(activity.window, area, pixels, { result ->
                if (epoch == generation && request.version == sampleVersion && result == PixelCopy.SUCCESS &&
                    !settingsVisible && view.width == request.width && view.height == request.height && view.hasWindowFocus()) {
                    val colors = IntArray(columns * rows)
                    pixels.getPixels(colors, 0, columns, 0, 0, columns, rows)
                    val color = SidebarBackdropColor.representative(colors, request.left)
                    val hex = String.format(Locale.ROOT, "#%06X", color)
                    if (hex != lastColor) {
                        lastColor = hex
                        channel?.postMessage(WebMessage(hex))
                    }
                }
                pixels.recycle()
            }, main)
        } catch (_: IllegalArgumentException) { pixels.recycle() }
    }

    fun back(onUnhandled: () -> Unit) {
        val view = browser
        if (view == null || !canHandleBack) { onUnhandled(); return }
        val epoch = generation
        view.evaluateJavascript("window.SugarShell?.back() === true") {
            if (epoch == generation && it != "true") onUnhandled()
        }
    }

    var pendingRecoveryRoute: String? = null

    fun reportRecovery(controller: RecoveryController) {
        if (recoveryVisible) channel?.postMessage(WebMessage(JSONObject().put("type", "recoveryState")
            .put("busy", controller.busy || controller.viewer).put("message", controller.message)
            .put("loadingMode", controller.loadingMode ?: JSONObject.NULL)
            .put("touched", controller.touched).toString()))
    }

    fun navigateAfterRecovery(route: String) {
        pendingRecoveryRoute = route
        deliverRecoveryRoute()
    }

    private fun deliverRecoveryRoute() {
        val route = pendingRecoveryRoute ?: return
        val host = channel ?: return
        host.postMessage(WebMessage(JSONObject().put("type", "recoveryDestination").put("route", route).toString()))
        pendingRecoveryRoute = null
    }

    fun consumeSystemEdge(side: String): Boolean {
        val host = channel ?: return false
        if (directoryPickerOpen) return false // Back dismisses the picker before the rail handles the edge.
        if (!expanded) host.postMessage(WebMessage("reveal-$side"))
        // A recognized system edge gesture never advances the app's page history.
        return true
    }

    fun detach() {
        mobileUi.detach()
        splashPreview.detach()
        SplashSettings.preferences(activity).unregisterOnSharedPreferenceChangeListener(splashChanges)
        lastBounds = null
        SugarDialog.updateDock(activity, false, 0)
        useFullEdges(false)
        generation++
        embeddedGesture?.detach()
        embeddedGesture = null
        embeddedPanelOpen = false
        invalidateSample()
        browser?.systemGestureExclusionRects = emptyList()
        browser = null
        adbSettings.detach()
        channel?.close()
        channel = null
        settingsVisible = false
        connectionSettingsVisible = false
        licenseSettingsVisible = false
        recoveryVisible = false
        storageUsage.cancel()
        storageVisible = false
        sessionStorageVisible = false
        sessionStorage.close()
        workspaceFiles.page("", "")
        workspaceFilesVisible = false
        petVisible = false
        petSettings.closePreview()
        expanded = false
        lastColor = ""
        canHandleBack = false
        directoryPickerOpen = false
        historyChanged()
    }

    private companion object { val HEX = Regex("#[0-9a-fA-F]{6}") }
}

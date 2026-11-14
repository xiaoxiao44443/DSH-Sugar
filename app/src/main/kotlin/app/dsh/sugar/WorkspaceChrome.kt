package app.dsh.sugar

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import android.widget.FrameLayout
import app.dsh.sugar.shared.WorkspaceEndpoint

/** The page paints the entire window. Its controls and the native splash own their respective insets. */
internal class WorkspaceChrome(
    private val activity: Activity, private val root: FrameLayout,
    private val fallbackColor: () -> Int, private val safeAreaChanged: (Insets) -> Unit
) {
    constructor(activity: Activity, root: FrameLayout, splash: LaunchScreen) :
        this(activity,root,{ splash.paper },splash::updateInsets)

    private val window = activity.window
    private val main = Handler(Looper.getMainLooper())
    private var safe = Insets.NONE
    private var ime = 0
    private var dockLeft = 0
    private var dockRight = 0
    private var landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private var browser: WebView? = null
    private var channel: WebMessagePort? = null
    private var generation = 0L
    private val sample = Runnable { sampleIconBackgrounds() }

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        window.navigationBarDividerColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun installInsets() {
        var legacyBars = Insets.NONE
        var legacyKeyboard = 0
        val visible = Rect()
        val position = IntArray(2)
        fun applyLegacyInsets() {
            // Some API 29 IMEs update the visible display frame without dispatching an IME inset.
            root.getWindowVisibleDisplayFrame(visible)
            root.getLocationOnScreen(position)
            val overlap = position[1] + root.height - visible.bottom
            val minimumKeyboard = legacyBars.bottom + (80 * root.resources.displayMetrics.density).toInt()
            val keyboard = if (!visible.isEmpty && overlap > minimumKeyboard) maxOf(legacyKeyboard, overlap) else legacyKeyboard
            applyInsets(legacyBars, keyboard)
        }
        if (Build.VERSION.SDK_INT == 29) root.viewTreeObserver.addOnGlobalLayoutListener { applyLegacyInsets() }
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                applyInsets(bars, insets.getInsets(WindowInsets.Type.ime()).bottom)
                WindowInsets.CONSUMED
            } else {
                val cutout = insets.displayCutout
                legacyBars = Insets.of(maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    maxOf(minOf(insets.systemWindowInsetBottom, insets.stableInsetBottom), cutout?.safeInsetBottom ?: 0))
                legacyKeyboard = if (insets.systemWindowInsetBottom > legacyBars.bottom) insets.systemWindowInsetBottom else 0
                applyLegacyInsets()
                insets.consumeSystemWindowInsets().consumeStableInsets().consumeDisplayCutout()
            }
        }
    }

    init {
        configureWindow()
        installInsets()
        splashIcons()
    }

    private fun applyInsets(bars: Insets, keyboard: Int) {
        val effective = Insets.of(bars.left, bars.top, bars.right, if (keyboard > 0) 0 else bars.bottom)
        val wide = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (effective == safe && keyboard == ime && landscape == wide) return
        landscape = wide
        safe = effective
        ime = keyboard
        // Only the keyboard occludes the WebView. The status bar, cutout and gesture area remain on top of it.
        root.setPadding(0, 0, 0, ime)
        safeAreaChanged(safe)
        channel?.postMessage(WebMessage(insetMessage()))
        scheduleSample()
    }

    // A native dock may reserve space for controls. The WebView and its modal scrims
    // still paint the full window behind the dock, system bars and camera cutout.
    fun reserveDock(left: Int, right: Int) {
        if (dockLeft == left && dockRight == right) return
        dockLeft = left
        dockRight = right
        channel?.postMessage(WebMessage(insetMessage()))
    }

    private fun insetMessage() = "${safe.top},${safe.right + dockRight},${safe.bottom},${safe.left + dockLeft},${if (landscape) 1 else 0}"

    fun detach() {
        generation++
        root.removeCallbacks(sample)
        browser = null
        channel?.close()
        channel = null
        splashIcons()
    }

    fun attach(view: WebView, origin: String) {
        val endpoint = WorkspaceEndpoint.parse(origin) ?: return
        if (!endpoint.owns(view.url.orEmpty())) return
        browser = view
        val id = ++generation
        channel?.close()
        channel = null
        view.evaluateJavascript(OBSERVE.replace("INITIAL_INSETS", insetMessage())) {
            if (id != generation || !endpoint.owns(view.url.orEmpty()) || activity.isDestroyed) return@evaluateJavascript
            val (host, page) = view.createWebMessageChannel()
            channel = host
            host.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(port: WebMessagePort, message: WebMessage) {
                    if (channel !== host || !endpoint.owns(view.url.orEmpty()) || message.data != "contrast") return
                    scheduleSample()
                }
            })
            view.postWebMessage(WebMessage(ATTACH, arrayOf(page)), android.net.Uri.parse(endpoint.origin))
            host.postMessage(WebMessage(insetMessage()))
            scheduleSample()
        }
    }

    private fun scheduleSample() {
        if (browser == null) return
        root.removeCallbacks(sample)
        root.postDelayed(sample, 40)
    }

    private fun sampleIconBackgrounds() {
        val view = browser ?: return
        if (!view.isShown || view.width < 1 || view.height < 1 || activity.isDestroyed) return
        val id = generation
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val x = location[0] + view.width / 2
        fun read(y: Int, status: Boolean) {
            val row = location[1] + y.coerceIn(0, view.height - 1)
            val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(window, Rect(x, row, x + 1, row + 1), pixel, { result ->
                    if (id == generation && result == PixelCopy.SUCCESS && !activity.isDestroyed) {
                        setIcons(status, Color.luminance(pixel.getPixel(0, 0)) > 0.179f)
                    }
                    pixel.recycle()
                }, main)
            } catch (_: IllegalArgumentException) {
                // A window may lose its surface while an activity is backgrounded.
                pixel.recycle()
            }
        }
        if (safe.top > 0) read(safe.top / 2, true)
        if (safe.bottom > 0) read(view.height - 1 - safe.bottom / 2, false)
    }

    fun splashIcons() {
        val light = Color.luminance(fallbackColor()) > .179f
        setIcons(true, light)
        setIcons(false, light)
    }

    @Suppress("DEPRECATION")
    private fun setIcons(status: Boolean, light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val flag = if (status) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) flag else 0, flag)
        } else {
            val flag = if (status) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val decor = window.decorView
            decor.systemUiVisibility = if (light) decor.systemUiVisibility or flag else decor.systemUiVisibility and flag.inv()
        }
    }

    private companion object {
        const val ATTACH = "dsh-sugar/observe-edges"
        // Layout classes below are from the bundled DSH AppFrame. Portals render directly into body.
        // Keep frame-chrome-top at zero: the primitives mask paints its background on ::after.
        val OBSERVE = """
            (() => {
              const hook = '__dshSugarEdgeColors';
              if (window[hook]) window[hook]();
              const sheet = document.createElement('style');
              sheet.id = 'dsh-sugar-safe-area';
              sheet.textContent = `
                html { --dsh-frame-overlay-top: calc(var(--sugar-safe-top) + 20px); }
                html .pI_x6G_frame { padding-left: var(--sugar-safe-left); padding-right: var(--sugar-safe-right); }
                html .pI_x6G_centerCol, html .pI_x6G_sidebarCol, html [data-sidebar-right-panel] {
                  box-sizing: border-box; padding-top: var(--sugar-safe-top); padding-bottom: var(--sugar-safe-bottom);
                }
                html [data-sidebar-drawer] > .pI_x6G_sidebarCol { padding-left: var(--sugar-safe-left); }
                /* The right column ends above shell.bottom. Anchor a fullscreen dock to the
                   entire frame so it covers that row, including the system gesture area. */
                html [data-rightbar-col]:has([data-sidebar-right-panel="fullscreen"]) { position: static; }
                html [data-sidebar-right-panel="fullscreen"] {
                  padding-left: var(--sugar-safe-left); padding-right: var(--sugar-safe-right); padding-bottom: 0;
                }
                html [data-sidebar-right-panel="fullscreen"] :is([data-dockkit-pane], [data-dockkit-empty]) {
                  box-sizing: border-box; padding-bottom: var(--sugar-safe-bottom);
                }
                html [data-sidebar-right-panel="fullscreen"] [data-dockkit-pane]:has([data-slot="sidebar.right.tab.document"] iframe) {
                  padding-bottom: 0;
                }
                html [data-sidebar-right-panel="fullscreen"][data-sidebar-right-open] [data-dockkit-empty] {
                  pointer-events: auto;
                }
                /* The Android camera gutter is outside the DSH grid. Continue the adjacent
                   surfaces through it without displacing their title, controls or document. */
                html[data-sugar-landscape] .pI_x6G_frame:not([data-sidebar-drawer]):not([data-rightbar-fullscreen]) {
                  background-image: linear-gradient(var(--dsw-specific-sidebar-fill), var(--dsw-specific-sidebar-fill));
                  background-size: var(--sugar-safe-left) 100%;
                  background-position: left top;
                  background-repeat: no-repeat;
                }
                html[data-sugar-landscape] [data-sidebar-right-panel][data-sidebar-right-open] {
                  background-image: linear-gradient(var(--dsw-alias-bg-base), var(--dsw-alias-bg-base));
                  background-size: 100% var(--sugar-safe-top);
                  background-position: top;
                  background-repeat: no-repeat;
                }
                html[data-sugar-landscape] [data-sidebar-right-panel="fullscreen"][data-sidebar-right-open] {
                  background-color: var(--dsw-alias-bg-base);
                }
                /* Keep the project menu reachable on touch screens when its session list is collapsed. */
                @media (hover: none) {
                  [role="treeitem"][data-row-key^="workspace:"][aria-expanded="false"] > span:has(> button[data-context-menu-trigger]) {
                    display: inline-flex;
                  }
                  [role="treeitem"][data-row-key^="workspace:"][aria-expanded="false"] > span > button[data-context-menu-trigger] ~ * {
                    display: none;
                  }
                }
                html [data-shell-leading] { margin-top: var(--sugar-safe-top); margin-left: var(--sugar-safe-left); }
                html [data-shell-bottom]:not(:empty) { padding-bottom: var(--sugar-safe-bottom); }
                html .pI_x6G_frame:has(> [data-shell-bottom]:not(:empty)) > .pI_x6G_centerCol { padding-bottom: 0; }
                html ._44HXVa_overlay, html .tQSFJa_onboardingOverlay {
                  box-sizing: border-box; padding: var(--sugar-safe-top) var(--sugar-safe-right)
                    var(--sugar-safe-bottom) var(--sugar-safe-left);
                }
                html ._root_14mp3_6, html .VOzbGW_overlay {
                  box-sizing: border-box; padding-block: max(24px, var(--dsh-frame-overlay-top))
                    max(24px, var(--sugar-safe-bottom)); padding-inline: max(24px, var(--sugar-safe-left))
                    max(24px, var(--sugar-safe-right));
                }
              `;
              document.head.appendChild(sheet);
              const apply = value => {
                if (typeof value !== 'string' || !/^\d+,\d+,\d+,\d+,[01]$/.test(value)) return;
                const sides = ['top','right','bottom','left'];
                const insets = value.split(',');
                sides.forEach((side,i) => document.documentElement.style
                  .setProperty('--sugar-safe-' + side, (Number(insets[i]) / devicePixelRatio) + 'px'));
                document.documentElement.toggleAttribute('data-sugar-landscape', insets[4] === '1');
              };
              apply('INITIAL_INSETS');
              let port, frame = 0;
              const signal = () => {
                if (port && !frame) frame = requestAnimationFrame(() => {
                  frame = 0; port.postMessage('contrast');
                });
              };
              const observer = new MutationObserver(signal);
              observer.observe(document.documentElement, {subtree:true, attributes:true, attributeFilter:[
                'data-ds-dark-theme','data-sidebar-collapsed','data-sidebar-drawer','data-rightbar-fullscreen',
                'data-rightbar-collapsed','data-sidebar-right-open']});
              if (document.body) observer.observe(document.body, {childList:true, attributes:true,
                attributeFilter:['class','style','data-ds-dark-theme']});
              const app = document.getElementById('root');
              if (app) observer.observe(app, {childList:true});
              const finished = e => {
                if (e.pseudoElement || e.target?.matches?.('[role="dialog"],[data-sidebar-right-panel],.pI_x6G_sidebarCol')) signal();
              };
              const connect = e => {
                if (e.data !== '$ATTACH' || e.ports.length !== 1 || port) return;
                port = e.ports[0]; port.onmessage = m => apply(m.data);
                window.removeEventListener('message', connect); signal();
              };
              window.addEventListener('message', connect);
              window.addEventListener('resize', signal);
              window.addEventListener('sugar-surface-color', signal);
              window.addEventListener('focus', signal);
              document.addEventListener('visibilitychange', signal);
              document.addEventListener('animationend', finished, true);
              document.addEventListener('transitionend', finished, true);
              window[hook] = () => {
                observer.disconnect(); cancelAnimationFrame(frame); sheet.remove();
                document.documentElement.removeAttribute('data-sugar-landscape');
                window.removeEventListener('message', connect); window.removeEventListener('resize', signal);
                window.removeEventListener('sugar-surface-color', signal);
                window.removeEventListener('focus', signal); document.removeEventListener('visibilitychange', signal);
                document.removeEventListener('animationend', finished, true);
                document.removeEventListener('transitionend', finished, true);
                if (port) port.close();
                delete window[hook];
              };
            })()
        """.trimIndent()
    }
}

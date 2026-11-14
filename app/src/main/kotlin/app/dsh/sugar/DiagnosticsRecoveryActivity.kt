package app.dsh.sugar

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

class DiagnosticsRecoveryActivity : Activity() {
    private lateinit var controller: RecoveryController
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private val actions = mutableListOf<View>()
    private val systemEdge = SystemBackEdge(this)
    private lateinit var navigation: RecoveryNavigation
    private var destination = "back"
    private var finishingAfterCleanup = false
    private var management: RecoveryManagementWeb? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val retained = lastNonConfigurationInstance as? RecoveryController
            ?: RecoveryController.retained(savedInstanceState?.getString("controller"))
        controller = retained ?: RecoveryController(applicationContext)
        destination = savedInstanceState?.getString(DESTINATION) ?: "back"
        finishingAfterCleanup = savedInstanceState?.getBoolean("leaving") == true
        try {
            val labels = RecoveryController.labels(this)
                .put("diag_title",sugarString(R.string.diag_title)).put("diag_nav_home",sugarString(R.string.diag_nav_home))
            management = RecoveryManagementWeb(this,labels,savedInstanceState?.getString("railSide"),::act,::nativeFallback)
            management?.show()
        } catch (_: RuntimeException) {
            nativeFallback()
        }
        controller.attach(this,::render)
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = systemEdge.callback(::back).also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        if (retained == null && savedInstanceState?.getBoolean("active") == true) {
            controller.cleanup(sugarString(R.string.diag_interrupted))
        }
        render()
    }

    private fun act(id: String) {
        if (controller.busy || controller.viewer || finishingAfterCleanup) return
        if (id == "home" || id == "back") leave(id) else controller.action(id)
    }

    private fun nativeFallback() {
        management?.dispose()
        management = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0,dp(12),0,dp(24))
        }
        fun label(resource: Int, size: Float, bold: Boolean = false) =
            TextView(this).apply {
                text = sugarString(resource)
                textSize = size
                setTextColor(getColor(if (size < 16) R.color.sugar_secondary else R.color.sugar_ink))
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0,dp(10),0,dp(6))
            }
        content.addView(label(R.string.diag_title,30f,true))
        content.addView(label(R.string.diag_intro,14f))
        actions.addAll(RecoveryActionGroup.addTo(content,
            onCurrent = { act("current") }, onBuiltin = { act("builtin") },
            onRestore = { act("restore") }, onReset = { act("reset") }))
        spinner = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.sugar_accent))
        }
        content.addView(spinner,LinearLayout.LayoutParams(-1,dp(4)).apply { topMargin = dp(15) })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.sugar_secondary))
            setPadding(0,dp(8),0,dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        navigation = RecoveryNavigation(this, content, intent.getBundleExtra(SETTINGS), ::leave)
        showRecoveryNavigation(navigation)
        render()
    }

    private fun render() {
        if (controller.touched || destination != "back") {
            setResult(RESULT_OK, Intent().putExtra(DESTINATION, destination).putExtra(RECONNECT, controller.touched))
        }
        val busy = controller.busy || controller.viewer
        for (button in actions) button.isEnabled = !busy && !finishingAfterCleanup
        management?.update(busy || finishingAfterCleanup,controller.message,controller.loadingMode)
        if (::spinner.isInitialized) spinner.visibility = if (controller.busy) View.VISIBLE else View.GONE
        if (::status.isInitialized) status.text = controller.message
        if (finishingAfterCleanup && !busy) finish()
    }

    private fun leave(route: String) {
        if (finishingAfterCleanup || controller.busy || controller.viewer) return
        destination = route
        finishingAfterCleanup = true
        render()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        systemEdge.observe(event)
        return super.dispatchTouchEvent(event)
    }

    @Deprecated("Platform back dispatch")
    @android.annotation.SuppressLint("GestureBackNavigation") // Android 10–12. Android 13+ uses the callback registered above.
    override fun onBackPressed() = back(systemEdge.take())

    private fun back(edge: String?) {
        val page = management
        if (page != null) page.back(edge) else if (::navigation.isInitialized) navigation.back(edge)
    }

    @Deprecated("Platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        controller.viewerResult(requestCode,resultCode)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("railSide",management?.side)
        outState.putString("controller", controller.key)
        outState.putString(DESTINATION, destination)
        outState.putBoolean("leaving", finishingAfterCleanup)
        outState.putBoolean("active", controller.busy || controller.viewer || controller.touched)
    }
    override fun onRetainNonConfigurationInstance(): Any = controller
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        if (isFinishing) controller.requestExit()
        controller.detach(this)
        management?.dispose()
        management = null
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onResume() { super.onResume(); controller.resume(this) }
    override fun onPause() { controller.pause(this); super.onPause() }
    companion object {
        internal const val REQUEST = 0x5512
        internal const val DESTINATION = "destination"
        internal const val RECONNECT = "reconnect"
        private const val SETTINGS = "settings"
        fun open(activity: Activity, settings: Bundle? = null) {
            val intent = Intent(activity, DiagnosticsRecoveryActivity::class.java).putExtra(SETTINGS, settings)
            activity.startActivityForResult(intent, REQUEST)
        }
    }
}

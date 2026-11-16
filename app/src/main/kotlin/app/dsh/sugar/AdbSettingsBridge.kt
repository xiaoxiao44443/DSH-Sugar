package app.dsh.sugar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import kotlin.concurrent.thread

internal class AdbSettingsBridge(private val activity: Activity, private val publish: (JSONObject) -> Unit) {
    private val app = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adb by lazy { WirelessAdb.get(app) }
    private val root by lazy { RootAccess.get(app) }
    private var attached = false
    private var pendingDiscovery: Boolean? = null
    private val refresh: () -> Unit = { main.post { if (attached) report() }; Unit }
    private val refreshRoot: () -> Unit = { main.post { if (attached) reportRoot() }; Unit }
    fun attach() {
        attached = true
        adb.addListener(refresh)
        root.addListener(refreshRoot)
        AdbPairingNotice.listen(refresh)
    }
    fun detach() {
        if (!attached) return
        attached = false
        pendingDiscovery = null
        adb.removeListener(refresh)
        root.removeListener(refreshRoot)
        AdbPairingNotice.unlisten(refresh)
    }
    private fun report(error: String? = null) {
        if (!attached) return
        val state = adb.status().put("type","adbState")
        val phase = AdbPairingNotice.phase ?: if (pendingDiscovery != null) "requestingPermission" else null
        if (phase != null) state.put("busy", true).put("phase", phase)
        state.put("cancellable", AdbPairingNotice.active)
        (error ?: AdbPairingNotice.message(app))?.let { state.put("message", it) }
        publish(state)
    }
    private fun reportRoot() {
        if (attached) publish(root.status().put("type", "rootState"))
    }
    fun resumedLanguage() { report(); reportRoot() }
    fun resumed() {
        AdbPairingNotice.refresh()
        if (attached) { report(); reportRoot() }
    }
    private fun openSettings() {
        for (target in listOf("android.settings.WIRELESS_DEBUG_SETTINGS", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { activity.startActivity(Intent(target)); return }
            catch (_: ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        report(app.sugarString(R.string.adb_open_settings))
    }
    private fun discover(pair: Boolean) {
        if (AdbPairingNotice.active || adb.status().optBoolean("connected")) { report(); return }
        if (Build.VERSION.SDK_INT >= 37 && activity.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            pendingDiscovery = pair
            report()
            activity.requestPermissions(arrayOf(LOCAL_NETWORK), REQUEST_DISCOVERY)
            return
        }
        AdbPairingNotice.start(app, pair)
        report()
        if (AdbPairingNotice.active && (pair || AdbPairingNotice.phase == "waitingForWireless")) openSettings()
    }
    fun permissionResult(code: Int, results: IntArray) {
        if (code != REQUEST_DISCOVERY) return
        val pair = pendingDiscovery ?: return
        pendingDiscovery = null
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) discover(pair)
        else report(app.sugarString(R.string.adb_network_permission))
    }
    fun handle(data: JSONObject, focused: Boolean) {
        val op = data.optString("type")
        if (op == "adbStatus") { report(); return }
        if (op == "rootStatus") { reportRoot(); return }
        if (op == "rootSetEnabled" || op == "rootProbe") {
            if (attached && focused) {
                if (op == "rootProbe") root.probe()
                else (data.opt("enabled") as? Boolean)?.let(root::setEnabled)
            }
            reportRoot()
            return
        }
        if (Build.VERSION.SDK_INT < 30 || !focused) { report(); return }
        when (op) {
            "openWirelessSettings" -> openSettings()
            "adbPairNotification", "adbAutoConnect" -> discover(op == "adbPairNotification")
            "adbConnect", "adbPair", "adbDisconnect" -> {
                val port = data.optInt("port", 0)
                val code = data.optString("code")
                if ((op != "adbDisconnect" && port !in 1..65535) ||
                    (op == "adbPair" && !code.matches(Regex("[0-9]{6}")))) { report(app.sugarString(R.string.adb_check_input)); return }
                if (AdbPairingNotice.active && op != "adbDisconnect") { report(); return }
                AdbPairingNotice.cancel(app)
                if (op == "adbDisconnect") {
                    pendingDiscovery = null
                    adb.disconnect()
                    report()
                    return
                }
                thread(name = "sugar-adb-settings") {
                    val result = runCatching { when (op) {
                        "adbConnect" -> adb.connect(port)
                        else -> adb.pair(port, code)
                    } }
                    main.post { report(if (result.isFailure) adb.status().optString("message").ifBlank { app.sugarString(R.string.adb_operation_failed) } else null) }
                }
            }
        }
    }
    companion object {
        private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val REQUEST_DISCOVERY = 6841
    }
}

package app.dsh.sugar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import java.util.UUID
import kotlin.concurrent.thread

/** A user-initiated attempt lives in the main process while the activity is in Developer options. */
internal object AdbPairingNotice {
    private const val CHANNEL = "adb_pairing"
    private const val ID = 571
    private const val KEY = "pairing_code"
    private const val WIRELESS = "adb_wifi_enabled"
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<() -> Unit>()
    private var flow: Flow? = null
    private var messageId: Int? = null
    fun message(context: Context): String? = messageId?.let { context.sugarString(it) }
    val phase: String? get() = flow?.phase
    val active: Boolean get() = flow != null
    fun listen(listener: () -> Unit) { observers.add(listener) }
    fun unlisten(listener: () -> Unit) { observers.remove(listener) }
    private fun update(text: Int?) {
        messageId = text
        observers.toList().forEach { it() }
    }
    fun languageChanged(app: Context) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.getNotificationChannel(CHANNEL)?.let { channel ->
            channel.name = app.sugarString(R.string.adb_pair_channel)
            manager.createNotificationChannel(channel)
        }
        val current = flow ?: return
        messageId?.let { show(app, it, current.replyId) }
    }
    fun refresh() { flow?.checkSwitch(resumed = true) }
    fun cancel(app: Context) {
        val old = flow
        flow = null
        old?.stop()
        app.getSystemService(NotificationManager::class.java).cancel(ID)
        update(null)
    }
    fun clearInactiveNotice(app: Context) {
        if (!active) app.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    fun start(app: Context, pair: Boolean) {
        if (active) return
        val adb = WirelessAdb.get(app)
        if (adb.status().optBoolean("connected")) { cancel(app); return }
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.sugarString(R.string.adb_pair_channel), NotificationManager.IMPORTANCE_HIGH)
            .apply { setShowBadge(false); setSound(null, null) })
        if (pair && !notificationsAllowed(app)) {
            update(R.string.adb_pair_notify_permission)
            return
        }
        Flow(app, pair).also { flow = it; it.start() }
    }
    private fun notificationsAllowed(app: Context): Boolean {
        val manager = app.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun action(app: Context, kind: String, id: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(app, AdbPairingReply::class.java).setAction(kind)
            .setData(Uri.Builder().scheme("sugar-adb").authority("pair").appendPath(id).build())
        val flag = if (!mutable) PendingIntent.FLAG_IMMUTABLE else if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or flag)
    }
    fun matches(entry: StatusBarNotification) = entry.id == ID && entry.notification.channelId == CHANNEL

    fun openPairing(context: Context): PendingIntent = PendingIntent.getActivity(context, ID,
        NotificationEntryActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)

    private fun show(app: Context, textId: Int, input: String? = null) {
        update(textId)
        val text = app.sugarString(textId)
        if (!notificationsAllowed(app)) return
        val pending = flow
        val open = openPairing(app)
        val notice = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_runtime_notification)
            .setContentTitle(app.sugarString(R.string.adb_pair_title)).setContentText(text).setContentIntent(open)
            .setStyle(Notification.BigTextStyle().bigText(text)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setOngoing(pending != null).setAutoCancel(pending == null)
        if (input != null) {
            val reply = RemoteInput.Builder(KEY).setLabel(app.sugarString(R.string.adb_pair_label)).build()
            notice.addAction(Notification.Action.Builder(null, app.sugarString(R.string.adb_pair_reply), action(app, "reply", input, true)).addRemoteInput(reply).build())
        }
        if (pending != null) notice.addAction(Notification.Action.Builder(null, app.sugarString(R.string.dialog_cancel), action(app, "cancel", pending.id)).build())
        app.getSystemService(NotificationManager::class.java).notify(ID, notice.build())
    }
    fun receive(app: Context, intent: Intent) {
        val current = flow
        if (current == null) { clearInactiveNotice(app); return }
        val key = intent.data?.lastPathSegment ?: return
        if (intent.action == "cancel" && key == current.id) {
            val inHandshake = current.phase == "connecting" || current.phase == "pairing"
            cancel(app)
            if (inHandshake) WirelessAdb.get(app).disconnect()
            return
        }
        if (intent.action == "reply" && key == current.replyId) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString()?.trim().orEmpty()
            current.pair(code)
        }
    }

    private class Flow(val app: Context, var pairing: Boolean) {
        val id = UUID.randomUUID().toString()
        var phase: String? = null
        var replyId: String? = null
        private var pairingPort: Int? = null
        private var attempt: CancellationSignal? = null
        private var scan: AdbPortDiscovery? = null
        private var generation = 0
        private var lastScan = 0L
        private val candidates = linkedSetOf<Int>()
        private val failures = mutableMapOf<Int, Int>()
        private val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { checkSwitch() }
        }
        private val poll = object : Runnable {
            override fun run() {
                if (flow !== this@Flow) return
                checkSwitch()
                if (flow === this@Flow) main.postDelayed(this, 5000)
            }
        }
        private val retry = Runnable {
            if (flow === this && attempt == null && (phase != "waitingForWireless" || switchEnabled() == null)) startScan()
        }
        private val timeout = Runnable {
            if (flow !== this) return@Runnable
            if (WirelessAdb.get(app).status().optBoolean("connected")) {
                attempt = null
                cancel(app)
                return@Runnable
            }
            if (switchEnabled() == false) { checkSwitch(); return@Runnable }
            finish(if (failures.isNotEmpty()) R.string.adb_timeout
                else R.string.adb_not_found)
        }
        fun start() {
            runCatching { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(WIRELESS), false, observer) }
            poll.run()
        }
        private fun switchEnabled(): Boolean? =
            try { when (Settings.Global.getString(app.contentResolver, WIRELESS)) { "0" -> false; "1" -> true; else -> null } }
            catch (_: SecurityException) { null }

        fun checkSwitch(resumed: Boolean = false) {
            if (flow !== this) return
            val enabled = switchEnabled()
            val waiting = phase == "waitingForWireless"
            if (enabled == false) {
                if (!waiting || scan != null) waitForWireless()
            } else if (enabled == null && (phase == null || waiting)) {
                // Some devices don't expose the switch. A verified local endpoint is also evidence it is on.
                if (!waiting) waitForWireless()
                if (scan == null || (resumed && SystemClock.elapsedRealtime() - lastScan > 2500)) startScan()
            } else if (phase == null || waiting) {
                beginSearch()
            } else if (resumed && phase in setOf("discoveringConnect", "discoveringPair") &&
                attempt == null && SystemClock.elapsedRealtime() - lastScan > 2500) {
                // Some OEMs suspend discovery callbacks while Settings is in front.
                startScan()
            }
        }
        private fun waitForWireless() {
            resetSearch()
            phase = "waitingForWireless"
            show(app, if (pairing) R.string.adb_enable_and_pair
                else R.string.adb_enable_and_connect)
        }
        private fun stopScan() {
            generation++
            scan?.stop()
            scan = null
            main.removeCallbacks(retry)
        }
        private fun resetSearch() {
            stopScan()
            main.removeCallbacks(timeout)
            val running = attempt
            attempt = null
            running?.cancel()
            candidates.clear()
            failures.clear()
            pairingPort = null
            replyId = null
        }
        fun stop() {
            resetSearch()
            main.removeCallbacks(poll)
            runCatching { app.contentResolver.unregisterContentObserver(observer) }
        }
        private fun finish(text: Int) {
            cancel(app)
            show(app, text)
        }
        private fun beginSearch() {
            resetSearch()
            phase = if (pairing) "discoveringPair" else "discoveringConnect"
            show(app, if (pairing) R.string.adb_choose_pair else R.string.adb_searching)
            armSearchTimeout()
            startScan()
        }
        private fun armSearchTimeout() {
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, if (pairing) 180000 else 45000)
        }
        private fun startScan() {
            stopScan()
            val epoch = generation
            lastScan = SystemClock.elapsedRealtime()
            val next = AdbPortDiscovery(app, if (pairing) "_adb-tls-pairing._tcp" else "_adb-tls-connect._tcp", { port ->
                if (epoch == generation && flow === this) {
                    if (port != null && phase == "waitingForWireless") {
                        phase = if (pairing) "discoveringPair" else "discoveringConnect"
                        armSearchTimeout()
                    }
                    if (pairing) {
                        if (port != pairingPort && attempt == null) {
                            pairingPort = port
                            replyId = port?.let { UUID.randomUUID().toString() }
                            phase = if (port == null) "discoveringPair" else "awaitingCode"
                            show(app, if (port == null) R.string.adb_pair_closed else R.string.adb_pair_keep_open, replyId)
                        }
                    } else if (port != null && (failures[port] ?: 0) < 2) {
                        candidates.add(port)
                        tryNext()
                    }
                }
            }, {
                if (epoch == generation && flow === this && attempt == null) {
                    main.removeCallbacks(retry)
                    main.postDelayed(retry, 1500)
                }
            })
            scan = next
            next.start()
        }
        fun pair(code: String) {
            val port = pairingPort ?: return
            if (attempt != null) return
            if (!code.matches(Regex("[0-9]{6}"))) { show(app, R.string.adb_six_digits, replyId); return }
            stopScan()
            pairingPort = null
            replyId = null
            perform(port, code)
        }
        private fun tryNext() {
            if (attempt != null) return
            val port = candidates.firstOrNull() ?: return
            candidates.remove(port)
            perform(port, null)
        }
        private fun perform(port: Int, code: String?) {
            val signal = CancellationSignal()
            attempt = signal
            phase = if (code == null) "connecting" else "pairing"
            show(app, if (code == null) R.string.adb_connecting else R.string.adb_pairing)
            thread(name = "sugar-adb-handshake") {
                val adb = WirelessAdb.get(app)
                val result = runCatching { if (code == null) adb.connect(port, signal) else adb.pair(port, code, signal) }
                main.post {
                    if (flow !== this || attempt !== signal) return@post
                    attempt = null
                    if (result.isSuccess) {
                        if (code != null) { pairing = false; phase = null; checkSwitch() }
                        else cancel(app)
                    } else if (switchEnabled() == false) {
                        checkSwitch()
                    } else if (code != null) {
                        finish(R.string.adb_pair_failed)
                    } else {
                        failures[port] = (failures[port] ?: 0) + 1
                        phase = "discoveringConnect"
                        show(app, R.string.adb_next_port)
                        tryNext()
                        if (attempt == null) {
                            main.removeCallbacks(retry)
                            main.postDelayed(retry, 1500)
                        }
                    }
                }
            }
        }
    }
}

internal class AdbPairingReply : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AdbPairingNotice.receive(context.applicationContext, intent)
}

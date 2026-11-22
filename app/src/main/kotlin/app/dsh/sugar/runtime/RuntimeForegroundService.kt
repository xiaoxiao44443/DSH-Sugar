package app.dsh.sugar.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import app.dsh.sugar.AdbPairingNotice
import app.dsh.sugar.DesktopPetOverlay
import app.dsh.sugar.WirelessAdb
import app.dsh.sugar.NotificationEntryActivity
import app.dsh.sugar.AgentNotifications
import app.dsh.sugar.VirtualTakeoverOverlay
import app.dsh.sugar.R
import app.dsh.sugar.sugarString

/** Owns the local agent independently of its WebView, including after a sticky service restart. */
internal class RuntimeForegroundService : Service() {
    interface Listener {
        fun onProgress(message: String)
        fun onConnecting()
        fun onRestarting()
        fun onReady(address: String)
        fun onFailed(message: String)
    }

    // Accessed on the main looper. A recreated activity can join an in-progress launch.
    private class Startup {
        val listeners = mutableSetOf<Listener>()
        var latest: ((Listener) -> Unit)? = null

        fun publish(update: (Listener) -> Unit) {
            latest = update
            listeners.toList().forEach(update)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var working: Startup? = null
    private val connected = mutableSetOf<Listener>()
    private var watcher: Thread? = null
    private var watchedId: String? = null
    private var inForeground = false
    private var ready = false
    private var petOnly = false
    private var lastRecovery: Long? = null
    private var desktopPet: DesktopPetOverlay? = null
    private var takeoverOverlay: VirtualTakeoverOverlay? = null

    private val adb by lazy { WirelessAdb.get(applicationContext) }
    private val adbChanged: () -> Unit = {
        MobileUseTasks.deviceChanged("virtual")
        main.post { if (instance === this) refreshNotification() }
        Unit
    }

    override fun onCreate() {
        super.onCreate()
        MobileUseTasks.initialize(this)
        takeoverOverlay = VirtualTakeoverOverlay(this).also { it.start() }
        AdbPairingNotice.clearInactiveNotice(this)
        adb.addListener(adbChanged)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_MOBILE) {
            intent.data?.lastPathSegment?.let { MobileUseTasks.stopFromNotice(it) }
            if (!inForeground && pending == null) stopSelf(startId)
            return if (inForeground) START_STICKY else START_NOT_STICKY
        }
        instance = this
        if (intent != null && pending == null && ready) return START_STICKY
        val startup = pending ?: Startup().also { pending = it }
        if (working === startup) return START_STICKY
        try {
            if (!inForeground) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL,
                    sugarString(R.string.sugar_runtime_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                })
                val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                startForeground(NOTICE, notification(), type)
                inForeground = true
                desktopPet = DesktopPetOverlay(this) {
                    if (petOnly && pending == null) stopSelf()
                }.also { it.start() }
            }
            startRuntime(startup)
        } catch (failure: RuntimeException) {
            stopRuntimeNotice(failure.localizedMessage.orEmpty())
            return if (inForeground) START_STICKY else START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startRuntime(startup: Startup) {
        working = startup
        if (petOnly) {
            petOnly = false
            refreshNotification()
        }
        Thread({
            try {
                val execution = SugarRuntime.start(applicationContext,
                    onProgress = { message -> publish(startup) { it.onProgress(message) } },
                    onConnecting = { publish(startup) { it.onConnecting() } },
                    onReady = { address -> main.post {
                        if (working === startup) {
                            ready = true
                            refreshNotification()
                            pending = null
                            connected.addAll(startup.listeners)
                            startup.publish { it.onReady(address) }
                            startup.listeners.clear()
                        }
                    } })
                main.post {
                    if (working === startup) {
                        working = null
                        watch(execution)
                    }
                }
            } catch (failure: Exception) {
                main.post { if (working === startup) stopRuntimeNotice(failure.localizedMessage.orEmpty()) }
            }
        }, "sugar-start").start()
    }

    private fun publish(startup: Startup, update: (Listener) -> Unit) {
        main.post { if (working === startup) startup.publish(update) }
    }

    private fun watch(id: String) {
        if (watchedId == id) return
        watcher?.interrupt()
        watchedId = id
        val thread = Thread({
            try {
                SugarRuntime.awaitExit(applicationContext, id)
            } catch (_: InterruptedException) {
                return@Thread
            } catch (_: Exception) {
                // Losing the execution handle also requires reconnecting to the host.
            }
            main.post {
                if (watchedId != id) return@post
                watchedId = null
                watcher = null
                // A concurrent reconnect already owns recovery and will install its own watch.
                if (working == null && pending == null && !SugarRuntime.inMaintenance) recoverHost()
            }
        }, "sugar-runtime-exit")
        watcher = thread
        thread.start()
    }

    private fun recoverHost() {
        val now = SystemClock.elapsedRealtime()
        val previous = lastRecovery
        if (previous != null && now - previous < RECOVERY_INTERVAL_MS) {
            stopRuntimeNotice(sugarString(R.string.sugar_runtime_exited))
            return
        }
        lastRecovery = now
        ready = false
        val startup = Startup()
        pending = startup
        val clients = connected.toList()
        connected.clear()
        refreshNotification()
        // Activity subscribes with a new session ID, so the fresh origin can load in its WebView.
        clients.forEach { it.onRestarting() }
        startRuntime(startup)
    }

    private fun stopRuntimeNotice(message: String, destroying: Boolean = false) {
        val clients = connected + pending?.listeners.orEmpty()
        connected.clear()
        working = null
        pending = null
        watchedId = null
        watcher?.interrupt()
        watcher = null
        ready = false
        lastRecovery = null
        // A sticky restart can restore the overlay even when the DSH worker fails to reconnect.
        petOnly = !destroying && inForeground && desktopPet?.enabled == true
        if (petOnly) {
            refreshNotification()
        } else {
            desktopPet?.close()
            desktopPet = null
            inForeground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!destroying) stopSelf()
        }
        clients.forEach { it.onFailed(message) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        desktopPet?.refresh()
        takeoverOverlay?.refresh()
    }

    override fun onDestroy() {
        takeoverOverlay?.close()
        takeoverOverlay = null
        MobileUseTasks.stopAll()
        adb.removeListener(adbChanged)
        if (instance === this) instance = null
        if (working != null || inForeground) stopRuntimeNotice(sugarString(R.string.sugar_start_error), destroying = true)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val openWorkspace = NotificationEntryActivity.intent(this).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val phoneTasks = MobileUseTasks.activeTasks()
        val controlled = phoneTasks.firstOrNull { it.controlState != "agent" }
        val tap = controlled?.let { AgentNotifications.openSession(this, it.conversation) }
            ?: PendingIntent.getActivity(this, 0, openWorkspace, PendingIntent.FLAG_IMMUTABLE)
        val status = when {
            petOnly -> R.string.pet_service_active
            ready -> R.string.sugar_runtime_running
            lastRecovery != null -> R.string.sugar_runtime_recovering
            else -> R.string.sugar_launching
        }
        val connection = adb.status()
        val text = when {
            controlled?.controlState == "closing" -> sugarString(R.string.mobile_virtual_closing)
            controlled?.controlState == "close_failed" -> sugarString(R.string.mobile_virtual_close_failed)
            controlled != null -> sugarString(R.string.mobile_virtual_control_notice)
            phoneTasks.any { !it.parked } -> sugarString(R.string.mobile_use_running)
            phoneTasks.isNotEmpty() -> sugarString(R.string.mobile_use_kept_screens)
            connection.optString("phase") == "connecting" -> sugarString(R.string.sugar_runtime_adb_connecting, sugarString(status))
            connection.getBoolean("connected") -> sugarString(R.string.sugar_runtime_adb_connected, sugarString(status))
            connection.getBoolean("paired") -> sugarString(R.string.sugar_runtime_adb_disconnected, sugarString(status))
            else -> sugarString(status)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_runtime_notification)
            .setContentTitle(sugarString(R.string.sugar_title))
            .setContentText(text)
            .setContentIntent(tap)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .apply {
                for (task in phoneTasks.take(3)) {
                    if (task.controlState == "closing") continue
                    val (id, screen, displayId, parked) = task
                    val stop = Intent(this@RuntimeForegroundService, RuntimeForegroundService::class.java)
                        .setAction(STOP_MOBILE).setData(android.net.Uri.parse("sugar://mobile-use/stop/$id"))
                    val click = PendingIntent.getService(this@RuntimeForegroundService, 0, stop, PendingIntent.FLAG_IMMUTABLE)
                    val label = when {
                        task.controlState == "close_failed" -> R.string.mobile_virtual_retry_close
                        parked -> R.string.mobile_virtual_stop
                        screen == "main" -> R.string.mobile_use_stop_main
                        else -> R.string.mobile_use_stop_virtual
                    }
                    val actionLabel = sugarString(label) + (displayId?.let { " #$it" } ?: "")
                    addAction(Notification.Action.Builder(null, actionLabel, click).build())
                }
            }
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    companion object {
        private const val STOP_MOBILE = "app.dsh.sugar.STOP_MOBILE_TASK"
        private const val CHANNEL = "local_runtime"
        private const val NOTICE = 1
        private const val RECOVERY_INTERVAL_MS = 60_000L
        private var pending: Startup? = null
        private var instance: RuntimeForegroundService? = null

        /** Called by the visible activity. A fresh request rechecks the worker and its login cookie. */
        fun start(context: Context, listener: Listener) {
            val previous = pending
            if (previous != null) {
                previous.listeners.add(listener)
                previous.latest?.invoke(listener)
                return
            }
            val startup = Startup()
            startup.listeners.add(listener)
            pending = startup
            try {
                context.startForegroundService(Intent(context, RuntimeForegroundService::class.java))
            } catch (failure: RuntimeException) {
                pending = null
                listener.onFailed(failure.localizedMessage.orEmpty())
            }
        }

        fun detach(listener: Listener) {
            pending?.listeners?.remove(listener)
            instance?.connected?.remove(listener)
        }

        fun languageChanged() {
            val service = instance ?: return
            service.desktopPet?.refresh()
            service.takeoverOverlay?.refresh()
            val manager = service.getSystemService(NotificationManager::class.java)
            manager.getNotificationChannel(CHANNEL)?.let { channel ->
                channel.name = service.sugarString(R.string.sugar_runtime_channel)
                manager.createNotificationChannel(channel)
            }
            refreshNotification()
        }

        fun refreshPetAfterResume() {
            instance?.desktopPet?.recoverAfterWake()
        }

        fun refreshNotification() {
            val service = instance ?: return
            if (!service.inForeground) return
            if (Build.VERSION.SDK_INT >= 33 &&
                service.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            service.getSystemService(NotificationManager::class.java).notify(NOTICE, service.notification())
        }
    }
}

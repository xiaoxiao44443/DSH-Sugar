package app.dsh.sugar

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.NetworkInterface
import java.net.SocketException
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** NSD callbacks, retries and the published candidate are owned by the main thread. Socket checks run off it. */
@Suppress("DEPRECATION")
internal class AdbPortDiscovery(
    context: Context, private val type: String, private val changed: (Int?) -> Unit,
    private val failed: () -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val checks = Executors.newSingleThreadExecutor { Thread(it, "sugar-adb-discovery").apply { isDaemon = true } }
    private val connecting = type == AdbDiscoveryCandidates.CONNECT
    private data class Key(val name: String, val network: Network?)
    private class Entry(val info: NsdServiceInfo) {
        var retries = 0
        var port: Int? = null
    }
    private val entries = mutableMapOf<Key, Entry>()
    private val queue = ArrayDeque<Entry>()
    private var resolving: Entry? = null
    private var resolver: NsdManager.ResolveListener? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var closed = false
    private val announcements = AdbPortAnnouncements(connecting)
    private var propertyPort: Int? = null

    private fun key(info: NsdServiceInfo) = Key(info.serviceName, if (Build.VERSION.SDK_INT >= 33) info.network else null)

    fun start() {
        if (closed || listener != null) return
        browse()
        if (connecting) pollProperty()
    }

    fun stop() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        endBrowse()
        checks.shutdownNow()
    }

    private fun endBrowse() {
        val old = listener
        listener = null
        entries.clear()
        queue.clear()
        resolving = null
        val pending = resolver
        resolver = null
        if (pending != null && Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(pending) }
        if (old != null) runCatching { nsd.stopServiceDiscovery(old) }
    }

    private val refresh = Runnable {
        if (!closed && !announcements.hasCandidates) {
            endBrowse()
            browse()
        }
    }

    private fun scheduleBrowse(delay: Long) {
        main.removeCallbacks(refresh)
        if (!closed && !announcements.hasCandidates) main.postDelayed(refresh, delay)
    }

    private fun browse() {
        if (closed) return
        val callbacks = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) { main.post {
                if (listener === this) scheduleBrowse(1500)
            } }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post {
                if (listener === this) scheduleBrowse(1500)
            } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (listener !== this || !AdbDiscoveryCandidates.matches(info.serviceType, type)) return@post
                val id = key(info)
                val found = Entry(info)
                entries.put(id, found)?.let { previous ->
                    found.port = previous.port
                    queue.remove(previous)
                }
                queue.add(found)
                resolveNext()
            } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post {
                if (listener !== this) return@post
                entries.remove(key(info))?.let(queue::remove)
                publish()
                if (!announcements.hasCandidates) scheduleBrowse(15_000)
            } }
        }
        listener = callbacks
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, callbacks)
            // Re-browse if NSD missed the first announcement while wireless debugging/Wi-Fi was off.
            scheduleBrowse(15_000)
        } catch (_: SecurityException) {
            if (!connecting) failed() // Connect discovery can still use the local adbd property.
        } catch (_: RuntimeException) {
            scheduleBrowse(1500)
        }
    }

    private fun resolveNext() {
        if (closed || resolving != null) return
        val entry = queue.poll() ?: return
        resolving = entry
        val callbacks = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { main.post {
                if (resolver !== this) return@post
                resolver = null
                resolving = null
                retry(entry)
                resolveNext()
            } }
            override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                if (resolver !== this) return@post
                resolver = null
                resolving = null
                if (entries[key(entry.info)] === entry) checkCandidate(entry, info)
                resolveNext()
            } }
        }
        resolver = callbacks
        try {
            nsd.resolveService(entry.info, callbacks)
            main.postDelayed({
                if (resolver === callbacks) {
                    resolver = null
                    resolving = null
                    if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(callbacks) }
                    retry(entry)
                    resolveNext()
                }
            }, 3000)
        } catch (_: RuntimeException) {
            resolver = null
            resolving = null
            retry(entry)
            main.post(::resolveNext)
        }
    }

    private fun retry(entry: Entry) {
        if (closed || entries[key(entry.info)] !== entry) return
        val delay = 350L shl minOf(entry.retries++, 4)
        main.postDelayed({
            if (!closed && entries[key(entry.info)] === entry && entry !== resolving && entry !in queue) {
                queue.addLast(entry)
                resolveNext()
            }
        }, delay)
    }

    private fun checkCandidate(entry: Entry, info: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val network = key(entry.info).network
        val port = info.port
        checks.execute {
            val links = runCatching { connectivity.getLinkProperties(network ?: connectivity.activeNetwork)?.linkAddresses.orEmpty() }
                .getOrDefault(emptyList())
            val local = AdbDiscoveryCandidates.isLocal(addresses) { host ->
                links.any { it.address == host } || try { NetworkInterface.getByInetAddress(host) != null }
                catch (_: SocketException) { false } catch (_: SecurityException) { false }
            }
            val verified = AdbDiscoveryCandidates.advertisedPort(port, local, connecting)
            main.post {
                if (closed || entries[key(entry.info)] !== entry) return@post
                entry.port = verified
                if (verified != null) Log.d("SugarAdbDiscovery", "Resolved local $type port $port")
                publish()
                if (verified == null && (local || addresses.isEmpty())) retry(entry)
            }
        }
    }

    private fun pollProperty() {
        if (closed) return
        checks.execute {
            val port = AdbDiscoveryCandidates.verifiedPropertyPort(AdbDiscoveryCandidates.readSystemPort())
            main.post {
                if (!closed) {
                    if (port != null && propertyPort != port) Log.d("SugarAdbDiscovery", "Verified local adbd property port $port")
                    propertyPort = port
                    publish()
                    if (!closed) main.postDelayed(::pollProperty, 2500)
                }
            }
        }
    }

    private fun publish() {
        if (closed) return
        for (candidate in announcements.update(propertyPort, entries.values.mapNotNull { it.port })) {
            if (closed) return
            changed(candidate)
        }
    }
}

/** A connect prompt may need to authenticate against several STLS candidates. A pairing prompt has one code. */
internal class AdbPortAnnouncements(private val connecting: Boolean) {
    private var previous: Set<Int> = emptySet()
    val hasCandidates: Boolean get() = previous.isNotEmpty()

    fun update(propertyPort: Int?, advertised: List<Int>): List<Int?> {
        val available = buildSet {
            if (connecting && propertyPort != null) add(propertyPort)
            if (connecting) addAll(advertised) else advertised.firstOrNull()?.let(::add)
        }
        val changes = if (available.isEmpty() && previous.isNotEmpty()) listOf(null)
            else available.filterNot(previous::contains)
        previous = available
        return changes
    }
}

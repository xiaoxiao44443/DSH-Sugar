package app.dsh.sugar

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import io.github.muntashirakon.adb.WirelessAdbTransport
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Process-wide, explicitly authorized loopback connection. Only the paired identity survives a restart. */
internal class WirelessAdb private constructor(private val context: Context) {
    private class Link(val port: Int) {
        val socket = Socket()
        var transport: WirelessAdbTransport? = null
        var runningCalls = 0
        val disconnectCallbacks = mutableSetOf<() -> Unit>()
        @Volatile var failure: String? = null
    }

    private val directory = File(context.noBackupFilesDir, "wireless-adb")
    private val marker = File(directory, "paired")
    private val identity by lazy { WirelessAdbIdentity.load(directory) }
    private val supported = Build.VERSION.SDK_INT >= 30
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "sugar-adb-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var paired = marker.isFile
    private var link: Link? = null
    private var phase = if (paired) "paired" else "unpaired"
    private var messageId: Int? = null
    private var detail = ""

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyListeners() { listeners.forEach { runCatching(it) } }

    fun status(): JSONObject = synchronized(lock) {
        val connected = link?.transport != null
        JSONObject().put("supported", supported).put("paired", paired)
            .put("connected", connected).put("enabled", connected).put("busy", link?.let { it.transport == null || it.runningCalls > 0 } == true)
            .put("phase", if (supported) phase else "unsupported").put("port", link?.port ?: JSONObject.NULL)
            .put("message", messageId?.let { context.sugarString(it) } ?: detail)
    }

    fun pair(port: Int, code: String, signal: CancellationSignal? = null): JSONObject {
        require(code.length == 6 && code.all { it in '0'..'9' }) { "请输入六位数字配对码" }
        return open(port, code, signal)
    }

    fun connect(port: Int, signal: CancellationSignal? = null): JSONObject = open(port, null, signal)

    private fun open(port: Int, pairingCode: String?, signal: CancellationSignal?): JSONObject {
        signal?.throwIfCanceled()
        check(supported) { "无线调试需要 Android 11 或更高版本" }
        require(port in 1..65535) { "ADB 端口无效" }
        val pairing = pairingCode != null
        val client = synchronized(lock) {
            check(link == null) { "已有 ADB 操作或连接，请先断开" }
            check(pairing || paired) { "请先使用本机无线调试配对码完成配对" }
            Link(port).also {
                link = it
                phase = if (pairing) "pairing" else "connecting"
                messageId = null
                detail = ""
            }
        }
        notifyListeners()
        val deadline = timer.schedule({
            invalidate(client, if (pairing) R.string.adb_pair_deadline else R.string.adb_connect_deadline, onlyWhileOpening = true)
        }, 15, TimeUnit.SECONDS)
        try {
            signal?.setOnCancelListener { invalidate(client, R.string.adb_canceled) }
            signal?.throwIfCanceled()
            client.socket.tcpNoDelay = true
            client.socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 5000)
            if (pairingCode != null) {
                WirelessAdbTransport.pair(client.socket, identity, pairingCode)
                synchronized(lock) {
                    check(link === client)
                    marker.createNewFile()
                    paired = true
                    link = null
                    phase = "paired"
                    messageId = R.string.adb_paired
                }
                client.socket.close()
            } else {
                val transport = WirelessAdbTransport.connect(client.socket, identity, timer)
                synchronized(lock) {
                    check(link === client)
                    client.transport = transport
                    phase = "connected"
                    messageId = R.string.adb_connected
                    transport.startReader { reason -> invalidate(client, reason) }
                }
            }
        } catch (error: Exception) {
            val reason = if (pairing) R.string.adb_pair_invalid else R.string.adb_connect_failed
            invalidate(client, reason)
            throw IOException(client.failure ?: context.sugarString(reason), error)
        } finally {
            signal?.setOnCancelListener(null)
            deadline.cancel(false)
        }
        notifyListeners()
        return status()
    }

    fun disconnect(): JSONObject {
        synchronized(lock) { link }?.let { invalidate(it, R.string.adb_disconnected) }
        return status()
    }

    /** Clear authority before closing the socket. Late completions cannot re-enable a disconnected link. */
    private fun invalidate(client: Link, reason: Int, onlyWhileOpening: Boolean = false) =
        invalidate(client, context.sugarString(reason), onlyWhileOpening, reason)

    private fun invalidate(client: Link, reason: String, onlyWhileOpening: Boolean = false, resource: Int? = null) {
        val canceled = synchronized(lock) {
            if (link !== client || (onlyWhileOpening && client.transport != null)) return
            client.failure = reason
            link = null
            phase = if (paired) "paired" else "unpaired"
            messageId = resource
            detail = reason
            client.disconnectCallbacks.toList().also { client.disconnectCallbacks.clear() }
        }
        client.transport?.close(reason) ?: runCatching { client.socket.close() }
        canceled.forEach { runCatching(it) }
        notifyListeners()
    }

    fun shell(command: String): JSONObject {
        validateCommand(command)
        return withConnection { it.shell(command) }
    }

    fun execOut(command: String, stdout: OutputStream, stderr: OutputStream, signal: CancellationSignal? = null): Int {
        validateCommand(command)
        return withConnection { it.execOut(command, stdout, stderr, signal) }
    }

    fun pull(remotePath: String, target: OutputStream, signal: CancellationSignal? = null): Long =
        withConnection { it.pull(remotePath, target, signal) }

    fun push(source: InputStream, remotePath: String, signal: CancellationSignal? = null): Long =
        withConnection { it.push(source, remotePath, signal) }

    private fun validateCommand(command: String) {
        require(command.isNotBlank() && '\u0000' !in command && command.toByteArray().size <= 8192) {
            "请输入 1 至 8192 字节的有效 shell 命令"
        }
    }

    /** Only transport failure or manual disconnect revokes authority. A caller's file I/O can fail independently. */
    fun <T> withConnection(
        expected: WirelessAdbTransport? = null,
        onDisconnect: (() -> Unit)? = null,
        action: (WirelessAdbTransport) -> T,
    ): T {
        val (client, transport) = synchronized(lock) {
            val current = checkNotNull(link) { "请先在手机控制设置中连接本机无线调试" }
            val channel = checkNotNull(current.transport) { "本机无线调试尚未连接" }
            check(expected == null || channel === expected) { "本次 ADB 请求对应的连接已断开" }
            if (onDisconnect != null) current.disconnectCallbacks.add(onDisconnect)
            current.runningCalls++
            current to channel
        }
        try {
            val result = action(transport)
            synchronized(lock) {
                if (link !== client) throw IOException(client.failure ?: "本机无线调试已断开")
            }
            return result
        } finally {
            synchronized(lock) {
                if (onDisconnect != null) client.disconnectCallbacks.remove(onDisconnect)
                client.runningCalls--
            }
        }
    }

    fun currentTransport(): WirelessAdbTransport? = synchronized(lock) { link?.transport }

    companion object {
        @Volatile private var instance: WirelessAdb? = null
        fun get(context: Context): WirelessAdb = instance ?: synchronized(this) {
            instance ?: WirelessAdb(context.applicationContext).also { instance = it }
        }
    }
}

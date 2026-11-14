package io.github.muntashirakon.adb

import android.os.CancellationSignal
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class WirelessAdbIds {
    private val counter = AtomicInteger()
    fun next(): Int {
        val id = counter.incrementAndGet()
        return if (id == 0) counter.incrementAndGet() else id
    }
}

/** Keeps blocking caller-owned streams away from the TLS reader. Each channel buffers at most one unacknowledged WRTE. */
internal class WirelessAdbByteStreams(
    private val ids: WirelessAdbIds,
    private val maxPayload: Int,
    private val send: (ByteArray) -> Unit,
) {
    private val lock = Any()
    private var closed: IOException? = null
    private val channels = HashMap<Int, Channel>()

    fun execOut(command: String, stdout: OutputStream, stderr: OutputStream, signal: CancellationSignal?): Int =
        use("shell,v2,raw:exec </dev/null; $command", signal) { channel ->
            val decoder = WirelessAdbShellOutput(stdout, stderr)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val length = channel.read(buffer)
                if (length < 0) break
                decoder.accept(buffer, length)
            }
            decoder.exitCode()
        }

    fun <T> use(service: String, signal: CancellationSignal?, block: (Channel) -> T): T {
        if (signal?.isCanceled == true) throw InterruptedIOException("ADB 操作已取消")
        val bytes = (service + '\u0000').toByteArray()
        require(bytes.size <= maxPayload) { "ADB 服务请求超过设备协商长度" }
        val channel = synchronized(lock) {
            closed?.let { throw it }
            val next = Channel(ids.next(), maxPayload, send)
            channels[next.id] = next
            try { send(AdbProtocol.generateMessage(AdbProtocol.A_OPEN, next.id, 0, bytes)) } catch (error: Exception) {
                channels.remove(next.id)
                throw error
            }
            next
        }
        try {
            signal?.setOnCancelListener { channel.abort(InterruptedIOException("ADB 操作已取消")) }
            channel.awaitOpen()
            return block(channel)
        } finally {
            signal?.setOnCancelListener(null)
            synchronized(lock) { channels.remove(channel.id) }
            channel.close()
        }
    }

    fun receive(packet: AdbProtocol.Message): Boolean {
        if (packet.command == AdbProtocol.A_OPEN) return false
        val channel = synchronized(lock) { channels[packet.arg1] } ?: return false
        channel.receive(packet)
        return true
    }

    fun disconnect(error: IOException) {
        val pending = synchronized(lock) {
            closed = error
            channels.values.toList().also { channels.clear() }
        }
        pending.forEach { it.abort(error, notifyPeer = false) }
    }

    internal class Channel(
        val id: Int,
        private val maxPayload: Int,
        private val send: (ByteArray) -> Unit,
    ) : InputStream(), Closeable {
        private val monitor = Object()
        private var remote = 0
        private var remoteClosed = false
        private var awaitingAck = false
        private var pending: ByteArray? = null
        private var offset = 0
        private var failure: IOException? = null
        private var lastActivity = System.nanoTime()

        fun receive(packet: AdbProtocol.Message) = synchronized(monitor) {
            if (failure != null || remoteClosed) {
                if (packet.command == AdbProtocol.A_OKAY && packet.arg0 != 0) send(AdbProtocol.generateClose(id, packet.arg0))
                return@synchronized
            }
            when (packet.command) {
                AdbProtocol.A_OKAY -> {
                    if (remote == 0) remote = packet.arg0
                    if (remote == 0 || remote != packet.arg0) return@synchronized
                    awaitingAck = false
                }
                AdbProtocol.A_WRTE -> {
                    if (remote == 0 || remote != packet.arg0) return@synchronized
                    if (pending != null) {
                        abort(WirelessAdbStreamException("adbd 连续写入未获确认的数据包"))
                        return@synchronized
                    }
                    val data = packet.payload
                    if (data == null || data.isEmpty()) send(AdbProtocol.generateReady(id, remote))
                    else pending = data
                }
                AdbProtocol.A_CLSE -> {
                    if (packet.arg0 != 0 && packet.arg0 != remote) return@synchronized
                    remoteClosed = true
                }
                else -> return@synchronized
            }
            lastActivity = System.nanoTime()
            monitor.notifyAll()
        }

        private fun waitUntil(ready: () -> Boolean) {
            while (true) {
                failure?.let { throw it }
                if (ready()) return
                val left = TimeUnit.SECONDS.toNanos(30) - (System.nanoTime() - lastActivity)
                if (left <= 0) throw WirelessAdbStreamException("ADB 数据通道已 30 秒无响应")
                try { TimeUnit.NANOSECONDS.timedWait(monitor, left) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("ADB 操作已取消")
                }
            }
        }

        fun awaitOpen() = synchronized(monitor) {
            waitUntil { remote != 0 || remoteClosed }
            if (remote == 0) throw WirelessAdbStreamException("adbd 拒绝了服务请求")
        }

        /** At a message boundary the virtual display helper can legitimately be silent indefinitely. */
        fun awaitNextPacket() = synchronized(monitor) {
            while (pending == null && !remoteClosed && failure == null) {
                try { monitor.wait() } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("ADB stream interrupted")
                }
            }
            failure?.let { throw it }
            Unit
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }

        override fun read(bytes: ByteArray, off: Int, len: Int): Int = synchronized(monitor) {
            if (len == 0) return 0
            waitUntil { pending != null || remoteClosed }
            val data = pending ?: return -1
            val count = minOf(data.size - offset, len)
            data.copyInto(bytes, off, offset, offset + count)
            offset += count
            if (offset == data.size) {
                pending = null
                offset = 0
                if (!remoteClosed) send(AdbProtocol.generateReady(id, remote))
            }
            lastActivity = System.nanoTime()
            count
        }

        /** An early SYNC FAIL can arrive while adbd is still waiting for the remainder of a SEND. */
        fun write(bytes: ByteArray, length: Int = bytes.size, stopOnReply: Boolean = false): Boolean {
            var offset = 0
            while (offset < length) synchronized(monitor) {
                waitUntil { !awaitingAck || remoteClosed || (stopOnReply && pending != null) }
                if (stopOnReply && pending != null) return false
                if (remoteClosed) throw WirelessAdbStreamException("adbd 已关闭文件传输")
                val count = minOf(length - offset, maxPayload)
                awaitingAck = true
                lastActivity = System.nanoTime()
                send(AdbProtocol.generateMessage(AdbProtocol.A_WRTE, id, remote, bytes, offset, count))
                offset += count
            }
            return true
        }

        fun abort(error: IOException, notifyPeer: Boolean = true) = synchronized(monitor) {
            if (failure != null) return@synchronized
            failure = error
            monitor.notifyAll()
            if (notifyPeer && remote != 0 && !remoteClosed) runCatching { send(AdbProtocol.generateClose(id, remote)) }
        }

        override fun close() = abort(WirelessAdbStreamException("ADB 数据通道已关闭"))
    }
}

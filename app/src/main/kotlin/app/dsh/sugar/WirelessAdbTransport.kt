// This package gives the adapter access to libadb's protocol/crypto primitives.
package io.github.muntashirakon.adb

import app.dsh.sugar.WirelessAdbIdentity
import android.os.CancellationSignal
import org.conscrypt.Conscrypt
import org.json.JSONObject
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.InterruptedIOException
import java.net.Socket
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

internal class WirelessAdbTransport private constructor(
    private val raw: Socket,
    private val tls: SSLSocket,
    private val deadlines: ScheduledThreadPoolExecutor,
    private val maxPayload: Int,
) {
    private val closed = AtomicBoolean(false)
    // ADB credits limit the data in flight per stream. All streams share one ordered TLS writer.
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "sugar-adb-writer").apply { isDaemon = true }
    }
    private val ids = WirelessAdbIds()
    private val streams = WirelessAdbShellStreams(ids, ::send)
    private val bytes = WirelessAdbByteStreams(ids, maxPayload, ::send)
    @Volatile private var onClose: (String) -> Unit = {}

    fun startReader(onClose: (String) -> Unit) {
        this.onClose = onClose
        thread(isDaemon = true, name = "sugar-adb-reader") {
            try {
                while (!closed.get()) {
                    val packet = AdbProtocol.Message.parse(tls.inputStream, VERSION, MAX_DATA)
                    if (!bytes.receive(packet)) streams.receive(packet)
                }
            } catch (failure: IOException) {
                close("本机无线调试连接已断开（${failure.javaClass.simpleName}）")
            } finally {
                runCatching { tls.close() }
            }
        }
    }

    fun shell(command: String): JSONObject {
        val result = streams.await(streams.open(command, maxPayload))
        return JSONObject().put("stdout", result.stdout).put("stderr", result.stderr)
            .put("exitCode", result.exitCode).put("truncated", result.truncated)
    }

    fun execOut(command: String, stdout: OutputStream, stderr: OutputStream, signal: CancellationSignal?): Int =
        bytes.execOut(command, stdout, stderr, signal)

    /** Duplex service reserved for the packaged display owner. No extra TCP port or device files. */
    fun virtualDisplayHelper(apk: String, signal: CancellationSignal, run: (WirelessAdbByteStreams.Channel) -> Unit) {
        require(apk.startsWith("/") && apk.none { it == '\u0000' || it == '\n' })
        val quoted = WirelessAdbSync.quoted(apk)
        bytes.use("exec:CLASSPATH=$quoted exec app_process / app.dsh.sugar.mobile.shell.VirtualAgent 2>/dev/null", signal, run)
    }

    fun pull(path: String, output: OutputStream, signal: CancellationSignal?): Long {
        WirelessAdbSync.validatePath(path)
        return bytes.use("sync:", signal) { WirelessAdbSync.pull(it, path, output) }
    }

    fun push(source: InputStream, path: String, signal: CancellationSignal?): Long {
        WirelessAdbSync.validatePath(path)
        val temporary = WirelessAdbSync.temporarySibling(path)
        val quoted = WirelessAdbSync.quoted(temporary)
        var published = false
        try {
            val count = bytes.use("sync:", signal) { WirelessAdbSync.push(it, source, temporary) }
            if (signal?.isCanceled == true) throw InterruptedIOException("ADB 操作已取消")
            val move = shell("mv -fT -- $quoted ${WirelessAdbSync.quoted(path)}")
            if (move.getInt("exitCode") != 0) {
                throw WirelessAdbStreamException("无法保存设备文件：" + move.getString("stderr").take(512))
            }
            published = true
            return count
        } finally {
            if (!published && !closed.get()) runCatching { shell("rm -f -- $quoted") }
        }
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        streams.disconnect(IOException(reason))
        bytes.disconnect(IOException(reason))
        writer.shutdownNow()
        // Closing the raw socket interrupts TLS reads and writes without a blocking TLS shutdown.
        runCatching { raw.close() }
        onClose(reason)
    }

    private fun send(bytes: ByteArray) {
        if (closed.get()) throw IOException("本机无线调试已断开")
        try {
            writer.execute {
                val writing = AtomicBoolean(true)
                val deadline = deadlines.schedule({
                    if (writing.compareAndSet(true, false)) close("ADB 传输写入超时")
                }, 15, TimeUnit.SECONDS)
                try {
                    tls.outputStream.write(bytes)
                    tls.outputStream.flush()
                } catch (failure: IOException) {
                    close("ADB 传输写入失败（${failure.javaClass.simpleName}）")
                } finally {
                    writing.set(false)
                    deadline.cancel(false)
                }
            }
        } catch (failure: RejectedExecutionException) {
            throw IOException("本机无线调试已断开", failure)
        }
    }
    companion object {
        private const val VERSION = AdbProtocol.A_VERSION_SKIP_CHECKSUM
        private const val MAX_DATA = 64 * 1024
        private const val PEER_INFO_SIZE = 8192

        fun connect(socket: Socket, identity: WirelessAdbIdentity, deadlines: ScheduledThreadPoolExecutor): WirelessAdbTransport {
            val output = socket.getOutputStream()
            output.write(AdbProtocol.generateMessage(AdbProtocol.A_CNXN, VERSION, MAX_DATA,
                "host::features=shell_v2\u0000".toByteArray()))
            output.flush()
            val upgrade = AdbProtocol.Message.parse(socket.getInputStream(), VERSION, MAX_DATA)
            if (upgrade.command != AdbProtocol.A_STLS || upgrade.arg0 < AdbProtocol.A_STLS_VERSION_MIN) {
                throw IOException("该端口不支持无线调试的 TLS 连接")
            }
            output.write(AdbProtocol.generateStls())
            output.flush()
            val secured = secure(socket, identity)
            val banner = AdbProtocol.Message.parse(secured.inputStream, VERSION, MAX_DATA)
            val features = banner.payload?.toString(Charsets.UTF_8)?.trimEnd('\u0000')
                ?.substringAfter("::", "")?.split(';')?.firstOrNull { it.startsWith("features=") }
                ?.substringAfter('=')?.split(',').orEmpty()
            if (banner.command != AdbProtocol.A_CNXN || "shell_v2" !in features) {
                throw IOException("adbd 未接受当前配对身份或未提供 shell v2")
            }
            val maxPayload = minOf(MAX_DATA, banner.arg1)
            if (maxPayload < 4096) throw IOException("adbd 返回的最大包长度无效")
            return WirelessAdbTransport(socket, secured, deadlines, maxPayload)
        }

        fun pair(socket: Socket, identity: WirelessAdbIdentity, code: String) {
            val tls = secure(socket, identity)
            val exported = Conscrypt.exportKeyingMaterial(tls, PairingConnectionCtx.EXPORTED_KEY_LABEL, null, 64)
                ?: throw IOException("无法获取配对 TLS 密钥材料")
            val password = code.toByteArray(Charsets.US_ASCII) + exported
            exported.fill(0)
            val auth = try { PairingAuthCtx.createAlice(password) } finally { password.fill(0) }
                ?: throw IOException("无法开始无线配对")
            try {
                val input = DataInputStream(tls.inputStream)
                val output = DataOutputStream(tls.outputStream)
                val spake = exchange(input, output, 0, auth.msg)
                if (!auth.initCipher(spake)) throw IOException("配对认证失败")
                val info = ByteArray(PEER_INFO_SIZE)
                // Android Settings tokenizes the public key comment at whitespace.
                AndroidPubkey.encodeWithName(identity.certificate.publicKey as RSAPublicKey, "DSH-Sugar")
                    .copyInto(info, destinationOffset = 1)
                val ciphertext = auth.encrypt(info) ?: throw IOException("无法加密配对信息")
                val peer = auth.decrypt(exchange(input, output, 1, ciphertext))
                if (peer == null || peer.size != PEER_INFO_SIZE || peer[0].toInt() != 1 || peer[1].toInt() == 0) {
                    throw IOException("无线配对码无效或已过期")
                }
            } finally {
                auth.destroy()
            }
        }

        private fun secure(socket: Socket, identity: WirelessAdbIdentity): SSLSocket {
            val context = SslUtils.getSslContext(KeyPair(identity.key, identity.certificate))
            val tls = context.socketFactory.createSocket(socket, "127.0.0.1", socket.port, true) as SSLSocket
            tls.useClientMode = true
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.startHandshake()
            return tls
        }

        private fun exchange(input: DataInputStream, output: DataOutputStream, type: Int, data: ByteArray): ByteArray {
            output.writeByte(1)
            output.writeByte(type)
            output.writeInt(data.size)
            output.write(data)
            output.flush()
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != type) throw IOException("配对响应无效")
            val size = input.readInt()
            if (size !in 1..(2 * PEER_INFO_SIZE)) throw IOException("配对响应大小无效")
            return ByteArray(size).also(input::readFully)
        }
    }
}

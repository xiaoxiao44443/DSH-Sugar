package app.dsh.sugar.runtime

import android.os.CancellationSignal
import app.dsh.sugar.WirelessAdb
import app.dsh.sugar.RootAccess
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Data plane for project adb commands. DSH alone vouches for the calling shell's project and skill. */
internal class ProjectAdbCli(
    private val adb: WirelessAdb,
    private val rootAccess: RootAccess,
    private val mobileScope: Any,
    private val hasWorkspace: (String, String) -> Boolean
) : Closeable {
    private data class Validator(val port: Int, val token: String)
    private class Call(val socket: Socket) {
        val signal = CancellationSignal()
        var token: String? = null
        var workspace: Pair<String, String>? = null
        var rootCommand = false
        @Volatile var authorizing: HttpURLConnection? = null
        fun cancel() {
            runCatching { socket.close() }
            authorizing?.disconnect()
            signal.cancel()
        }
    }

    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private var server: ServerSocket? = null
    private var validator: Validator? = null
    private var closed = false
    // Only unauthenticated loopback requests occupy admission slots.
    private val pendingHeaders = Semaphore(12)
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "sugar-adb-cli").apply { isDaemon = true }
    }
    private val rootChanged: () -> Unit = {
        if (!rootAccess.status().optBoolean("enabled")) cancelMatching { it.rootCommand }
    }

    init { rootAccess.addListener(rootChanged) }

    fun register(port: Int, token: String): Int {
        require(port in 1..65535 && TOKEN.matches(token))
        val endpoint = Validator(port, token)
        val obsolete: List<Call>
        val dataPort = synchronized(lock) {
            check(!closed)
            obsolete = if (validator != null && validator != endpoint) calls.toList() else emptyList()
            val listener = server ?: ServerSocket(0, 12, InetAddress.getByName("127.0.0.1")).also {
                server = it
                thread(isDaemon = true, name = "sugar-adb-cli-listener") { accept(it) }
            }
            validator = endpoint
            listener.localPort
        }
        obsolete.forEach { it.cancel() }
        return dataPort
    }

    fun revoke(token: String, rootOnly: Boolean = false) {
        cancelMatching { it.token == token && (!rootOnly || it.rootCommand) }
        if (!rootOnly) MobileUseTasks.revoke(token)
    }
    fun prune() = cancelMatching { call -> call.workspace?.let { !hasWorkspace(it.first, it.second) } == true }
    fun remove(id: String) = cancelMatching { it.workspace?.first == id }

    private fun cancelMatching(match: (Call) -> Boolean) {
        val expired = synchronized(lock) { calls.filter(match) }
        expired.forEach { it.cancel() }
    }

    private fun accept(listener: ServerSocket) {
        while (!listener.isClosed) {
            pendingHeaders.acquireUninterruptibly()
            val socket = try {
                listener.accept()
            } catch (_: IOException) {
                pendingHeaders.release()
                break
            }
            val call = Call(socket)
            synchronized(lock) {
                if (closed) {
                    pendingHeaders.release()
                    socket.close()
                    return
                }
                calls.add(call)
            }
            try {
                workers.execute {
                    var awaitingHeader = true
                    try {
                        serve(call) {
                            awaitingHeader = false
                            pendingHeaders.release()
                        }
                    } catch (_: IOException) {
                        // The client closed its socket or supplied an incomplete header.
                    } finally {
                        if (awaitingHeader) pendingHeaders.release()
                        synchronized(lock) { calls.remove(call) }
                        call.cancel()
                    }
                }
            } catch (_: RejectedExecutionException) {
                pendingHeaders.release()
                synchronized(lock) { calls.remove(call) }
                call.cancel()
            }
        }
    }

    private fun authorize(validator: Validator, token: String, needGuide: Boolean, mobile: Boolean,
                          ui: Boolean, call: Call, rootCommand: String? = null): JSONObject {
        val url = URL("http://127.0.0.1:${validator.port}/authorize")
        val http = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        call.authorizing = http
        try {
            call.signal.throwIfCanceled()
            http.requestMethod = "POST"
            http.instanceFollowRedirects = false
            http.useCaches = false
            http.doOutput = true
            http.connectTimeout = 5000
            http.readTimeout = if (rootCommand == null) 5000 else 0
            http.setRequestProperty("Content-Type", "application/json")
            val nonce = UUID.randomUUID().toString()
            val body = JSONObject().put("token", token).put("needGuide", needGuide).put("nonce", nonce)
                .apply {
                    if (mobile) put("mobile", true)
                    if (ui) put("ui", true)
                    if (rootCommand != null) put("rootCommand", rootCommand)
                }.toString()
            http.setRequestProperty("Authorization", "Sugar-HMAC " + proof(validator, "request\n$body"))
            val bytes = body.toByteArray()
            http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) }
            val input = if (http.responseCode in 200..299) http.inputStream else http.errorStream
            checkNotNull(input) { "无法验证当前终端的 ADB 访问权限。" }
            val raw = input.use { readMessage(it.buffered(), 8192, false) }
            val signature = http.getHeaderField("X-Sugar-Validator-Proof").orEmpty()
            check(MessageDigest.isEqual(proof(validator, "response\n$nonce\n$raw").toByteArray(), signature.toByteArray())) {
                "ADB 命令授权服务身份校验失败。"
            }
            val response = JSONObject(raw)
            check(response.optBoolean("ok")) {
                response.optJSONObject("error")?.optString("message")?.take(300) ?: "当前终端无权使用 ADB。"
            }
            return response.getJSONObject("result")
        } finally {
            call.authorizing = null
            http.disconnect()
        }
    }

    private fun serve(call: Call, onAuthorized: () -> Unit) {
        val socket = call.socket
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = Frames(socket.getOutputStream())
        var clientName = "adb"
        try {
            val args = JSONObject(readMessage(input, 65536, true, socket))
            val token = args.getString("token")
            require(TOKEN.matches(token)) { "ADB 凭据无效。" }
            val op = args.getString("op")
            require(op in OPERATIONS) { "不支持此 adb 命令。" }
            val root = op == "root-exec" || op == "root-status"
            if (root) clientName = "sugar-root"
            val authority = synchronized(lock) {
                check(!closed)
                call.token = token // Register before calling DSH so a simultaneous revoke can cancel this request.
                call.rootCommand = op == "root-exec"
                checkNotNull(validator)
            }
            val mobile = op == "mobileUse"
            val needsDevice = !root && !mobile && op != "devices" && op != "get-state"
            val device = if (needsDevice) adb.currentTransport() else null
            val command = if (op == "shell" || op == "exec-out" || op == "root-exec") args.getString("command").also {
                val limit = if (root) 16384 else 8192
                require(it.isNotBlank() && '\u0000' !in it && it.toByteArray().size <= limit) { "设备命令过长或无效。" }
            } else null
            if (op == "root-exec") check(rootAccess.status().optBoolean("enabled")) {
                "请先在 Sugar 的连接设置中启用 Root，并在 Root 管理器中允许 Sugar。"
            }
            // Root scripts are explicit Android shell requests. Keep existing ADB
            // inspection separate so adb shell su retains Shell's own grant.
            val inspection = if (root) null else command?.let(AdbUiCommands::inspect)
            fun watchClient() {
                socket.soTimeout = 0
                thread(isDaemon = true, name = "sugar-adb-cli-peer") {
                    runCatching { input.read() }
                    call.cancel()
                }
            }
            // Ctrl-C while the user is reading an approval must withdraw it too.
            if (root) watchClient()
            val identity = authorize(authority, token, needsDevice, mobile, inspection?.action != null,
                call, if (op == "root-exec") command else null)
            val owner = identity.getString("id") to identity.getString("root")
            synchronized(lock) {
                call.signal.throwIfCanceled()
                check(validator == authority) { "此终端的 ADB 授权已失效。" }
                check(!closed && hasWorkspace(owner.first, owner.second)) { "此工作区已关闭。" }
                call.workspace = owner
                check(!needsDevice || device != null) { "请先在 Sugar 设置中连接本机无线调试。" }
            }
            onAuthorized()
            val size = if (op == "push") args.getLong("size").also { require(it >= 0) } else 0
            // After the upload body the client keeps its write side open until the exit frame.
            // EOF then means Ctrl-C, a lost PTY, or a downstream pipe which has gone away.
            if (!root) socket.soTimeout = 30000
            val body = Upload(input, size, ::watchClient)
            if (size == 0L && !root) watchClient()
            val stdout = output.channel(1)
            val stderr = output.channel(2)
            if (root) {
                call.signal.throwIfCanceled()
                if (op == "root-status") {
                    stdout.write((rootAccess.status().toString() + "\n").toByteArray(Charsets.UTF_8))
                    output.exit(0)
                } else {
                    output.exit(rootAccess.execOut(checkNotNull(command), stdout, stderr, call.signal))
                }
            } else if (mobile) {
                val actor = MobileUseTasks.Owner(mobileScope, owner.first, owner.second, identity.getString("conversation"))
                val result = MobileUseTasks.command(actor, token, identity.getBoolean("manual"),
                    identity.optString("taskId"), args) { call.signal.isCanceled }
                stdout.write(result.toString().toByteArray(Charsets.UTF_8))
                output.exit(0)
            } else if (device != null) {
                adb.withConnection(device, call::cancel) { transport ->
                    call.signal.throwIfCanceled()
                    val exit = when (op) {
                        "shell", "exec-out" -> {
                            val shellCommand = checkNotNull(command)
                            val ui = checkNotNull(inspection).agentAction { identity.getBoolean("manual") }
                            fun execute(routed: String) = transport.execOut(routed, stdout, stderr, call.signal)
                            if (ui == null) execute(shellCommand) else {
                                val actor = MobileUseTasks.Owner(mobileScope, owner.first, owner.second, identity.getString("conversation"))
                                MobileUseTasks.withAdbUi(actor, identity.optJSONObject("route"), ui, shellCommand, ::execute)
                            }
                        }
                        "pull" -> { transport.pull(remotePath(args), stdout, call.signal); 0 }
                        else -> {
                            check(transport.push(body, remotePath(args), call.signal) == size) { "文件传输不完整。" }
                            0
                        }
                    }
                    output.exit(exit)
                }
            } else {
                if (op == "devices") {
                    stdout.write("List of devices attached\n".toByteArray())
                    if (adb.status().getBoolean("connected")) stdout.write("sugar-local\tdevice\n".toByteArray())
                    stdout.write('\n'.code)
                } else {
                    check(adb.status().getBoolean("connected")) { "device not connected" }
                    stdout.write("device\n".toByteArray())
                }
                output.exit(0)
            }
        } catch (failure: Exception) {
            if (socket.isClosed) return
            val message = failure.localizedMessage?.take(400)?.ifBlank { null } ?: "请求失败。"
            output.channel(2).write(("$clientName: " + message + "\n").toByteArray())
            output.exit(1)
        }
    }

    private fun remotePath(args: JSONObject): String = args.getString("path").also {
        require(it.startsWith('/') && it.length > 1 && it.none { c -> c == '\u0000' || c == '\r' || c == '\n' }) {
            "请指定手机上的绝对文件路径。"
        }
    }

    private class Upload(private val input: InputStream, private var remaining: Long,
                         private val drained: () -> Unit) : InputStream() {
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining == 0L) return -1
            val count = input.read(bytes, off, minOf(remaining, len.toLong()).toInt())
            if (count < 0) throw EOFException("上传在文件末尾之前中断。")
            remaining -= count
            if (remaining == 0L) drained()
            return count
        }
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 255
        }
    }

    private class Frames(stream: OutputStream) {
        private val output = DataOutputStream(stream)
        fun channel(type: Int) = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var pos = off
                synchronized(output) {
                    while (pos < off + len) {
                        val n = minOf(off + len - pos, 65536)
                        output.writeByte(type)
                        output.writeInt(n)
                        output.write(b, pos, n)
                        pos += n
                    }
                }
            }
        }
        fun exit(code: Int) = synchronized(output) {
            output.writeByte(3)
            output.writeInt(4)
            output.writeInt(code)
            output.flush()
        }
    }

    override fun close() {
        rootAccess.removeListener(rootChanged)
        synchronized(lock) {
            closed = true
            server?.close()
        }
        cancelMatching { true }
        workers.shutdownNow()
    }

    companion object {
        private const val HEX = "0123456789abcdef"
        private fun proof(validator: Validator, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(validator.token.toByteArray(), "HmacSHA256"))
            return buildString(64) {
                for (b in mac.doFinal(message.toByteArray())) {
                    append(HEX[(b.toInt() and 255) ushr 4])
                    append(HEX[b.toInt() and 15])
                }
            }
        }
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private val OPERATIONS = setOf("devices", "get-state", "shell", "exec-out", "push", "pull", "mobileUse", "root-status", "root-exec")
        private fun readMessage(input: InputStream, limit: Int, newline: Boolean, headerSocket: Socket? = null): String {
            val data = ByteArrayOutputStream()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var lastTimeout = 0
            while (data.size() <= limit) {
                if (headerSocket != null) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw SocketTimeoutException("ADB 请求头超时。")
                    val timeout = TimeUnit.NANOSECONDS.toMillis(remaining).toInt().coerceAtLeast(1)
                    if (timeout != lastTimeout) {
                        headerSocket.soTimeout = timeout
                        lastTimeout = timeout
                    }
                }
                val next = input.read()
                if (newline && next == '\n'.code || !newline && next == -1) return data.toString("UTF-8")
                if (next == -1) throw EOFException("ADB 请求不完整。")
                data.write(next)
            }
            throw IOException("ADB 请求过大。")
        }
    }
}

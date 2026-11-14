package io.github.muntashirakon.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class WirelessAdbStreamException(message: String) : IOException(message)

internal data class WirelessAdbShellResult(val stdout: String, val stderr: String, val exitCode: Int, val truncated: Boolean)

/** Shell v2 responses are routed by local stream ID even when different shells use the same command. */
internal class WirelessAdbShellStreams(private val ids: WirelessAdbIds = WirelessAdbIds(), private val send: (ByteArray) -> Unit) {
    internal class Call(val id: Int) {
        var remote = 0
        val output = WirelessAdbShellOutput()
        val result = CompletableFuture<WirelessAdbShellResult>()
    }

    private val calls = HashMap<Int, Call>()
    private var closed: IOException? = null

    @Synchronized
    fun open(command: String, maxPayload: Int = Int.MAX_VALUE): Call {
        closed?.let { throw it }
        val call = Call(ids.next())
        val service = "shell,v2,raw:exec </dev/null; $command\u0000".toByteArray()
        require(service.size <= maxPayload) { "ADB 服务请求超过设备协商长度" }
        calls[call.id] = call
        try {
            send(AdbProtocol.generateMessage(AdbProtocol.A_OPEN, call.id, 0, service))
        } catch (failure: Exception) {
            calls.remove(call.id)
            throw failure
        }
        return call
    }

    fun await(call: Call): WirelessAdbShellResult {
        try {
            return call.result.get(15, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            cancel(call, "ADB 命令超过 15 秒，已关闭本次 shell")
        } catch (error: InterruptedException) {
            cancel(call, "ADB 命令已取消")
            Thread.currentThread().interrupt()
            throw WirelessAdbStreamException("ADB 命令已取消")
        } catch (error: ExecutionException) {
            throw (error.cause as? IOException ?: IOException("ADB shell 执行失败", error))
        }
        // The exit frame may have won the race with the deadline.
        try { return call.result.get() } catch (error: ExecutionException) {
            throw (error.cause as? IOException ?: IOException("ADB shell 执行失败", error))
        }
    }

    @Synchronized
    fun cancel(call: Call, reason: String) {
        if (calls[call.id] !== call) return
        calls.remove(call.id)
        call.result.completeExceptionally(WirelessAdbStreamException(reason))
        if (call.remote != 0) send(AdbProtocol.generateClose(call.id, call.remote))
    }

    @Synchronized
    fun disconnect(reason: IOException) {
        closed = reason
        val pending = calls.values.toList()
        calls.clear()
        pending.forEach { it.result.completeExceptionally(reason) }
    }

    @Synchronized
    fun receive(packet: AdbProtocol.Message) {
        if (closed != null) return
        if (packet.command == AdbProtocol.A_OPEN) {
            // No reverse services are registered on this client.
            send(AdbProtocol.generateClose(0, packet.arg0))
            return
        }
        val call = calls[packet.arg1]
        if (call == null) {
            // A delayed OKAY can be the first response to an OPEN that timed out.
            if (packet.command == AdbProtocol.A_OKAY && packet.arg0 != 0 && packet.arg1 != 0) {
                send(AdbProtocol.generateClose(packet.arg1, packet.arg0))
            }
            return
        }
        val remote = packet.arg0
        when (packet.command) {
            AdbProtocol.A_OKAY -> if (call.remote == 0 && remote != 0) call.remote = remote
            AdbProtocol.A_WRTE -> if (remote != 0 && remote == call.remote) {
                try {
                    call.output.accept(packet.payload ?: ByteArray(0))
                } catch (failure: WirelessAdbStreamException) {
                    cancel(call, failure.message ?: "ADB shell 输出格式无效")
                    return
                }
                send(AdbProtocol.generateReady(call.id, remote))
            }
            AdbProtocol.A_CLSE -> {
                // Some adbd versions send CLSE(0, id) for an established stream too.
                if (remote != 0 && remote != call.remote) return
                calls.remove(call.id)
                try {
                    if (call.remote == 0) throw WirelessAdbStreamException("adbd 未能打开 shell v2")
                    call.result.complete(call.output.finish())
                } catch (failure: WirelessAdbStreamException) {
                    call.result.completeExceptionally(failure)
                }
                // A final WRTE and CLSE can be consecutive. Do not echo CLSE.
            }
        }
    }
}

/** Shell v2 headers and their payloads can span multiple WRTE packets. */
internal class WirelessAdbShellOutput(
    private val binaryStdout: OutputStream? = null,
    private val binaryStderr: OutputStream? = null,
) {
    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()
    private val header = ByteArray(5)
    private var headerSize = 0
    private var channel = 0
    private var remaining = 0L
    private var exit: Int? = null
    private var truncated = false

    fun accept(bytes: ByteArray, length: Int = bytes.size) {
        var offset = 0
        while (offset < length) {
            if (remaining == 0L) {
                val count = minOf(5 - headerSize, length - offset)
                bytes.copyInto(header, headerSize, offset, offset + count)
                headerSize += count
                offset += count
                if (headerSize < 5) return
                headerSize = 0
                channel = header[0].toInt() and 255
                for (i in 1..4) remaining = remaining or ((header[i].toLong() and 255) shl (8 * (i - 1)))
                if (channel == 3 && remaining != 1L) throw WirelessAdbStreamException("shell v2 退出帧无效")
                continue
            }
            val count = minOf(remaining, (length - offset).toLong()).toInt()
            when (channel) {
                1, 2 -> {
                    val binary = if (channel == 1) binaryStdout else binaryStderr
                    if (binary != null) binary.write(bytes, offset, count)
                    else {
                        val keep = minOf(count, 64 * 1024 - stdout.size() - stderr.size())
                        (if (channel == 1) stdout else stderr).write(bytes, offset, keep)
                        if (keep < count) truncated = true
                    }
                }
                3 -> exit = bytes[offset].toInt() and 255
                // Ignore control and unknown channels as the AOSP shell client does.
            }
            remaining -= count
            offset += count
        }
    }

    fun exitCode(): Int {
        if (headerSize != 0 || remaining != 0L) throw WirelessAdbStreamException("shell v2 输出帧不完整")
        return exit ?: throw WirelessAdbStreamException("shell 未返回退出状态")
    }

    fun finish() = WirelessAdbShellResult(stdout.toString("UTF-8"), stderr.toString("UTF-8"), exitCode(), truncated)
}

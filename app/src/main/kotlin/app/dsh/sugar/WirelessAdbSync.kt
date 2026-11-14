package io.github.muntashirakon.adb

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** SYNC v1 for regular files. Wire lengths are independent of the negotiated ADB packet size. */
internal object WirelessAdbSync {
    private const val MAX_CHUNK = 64 * 1024
    private const val HEADER = 8

    fun validatePath(path: String) {
        require(path.startsWith('/') && path.substringAfterLast('/') !in listOf("", ".", "..") && '\u0000' !in path) {
            "请输入包含文件名的设备绝对路径"
        }
        require(path.toByteArray().size <= 1024) { "ADB 文件路径超过 1024 字节" }
    }

    fun temporarySibling(path: String): String =
        path.substringBeforeLast('/') + "/.dsh-adb-" + UUID.randomUUID().toString() + ".tmp"

    fun quoted(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private fun header(id: String, value: Int) = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()

    private fun request(channel: WirelessAdbByteStreams.Channel, id: String, path: String) {
        val data = path.toByteArray()
        require(data.size <= 1024) { "ADB SYNC 请求路径太长" }
        channel.write(header(id, data.size) + data)
    }

    private data class Reply(val id: String, val value: Int)

    private fun reply(input: DataInputStream): Reply {
        val bytes = ByteArray(HEADER)
        input.readFully(bytes)
        val id = String(bytes, 0, 4, Charsets.US_ASCII)
        val value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        if (id == "FAIL") {
            if (value !in 0..MAX_CHUNK) throw WirelessAdbStreamException("adbd 返回了无效错误长度")
            val detail = ByteArray(value).also(input::readFully).toString(Charsets.UTF_8).take(512)
            throw WirelessAdbStreamException("ADB 文件传输失败：$detail")
        }
        return Reply(id, value)
    }

    fun pull(channel: WirelessAdbByteStreams.Channel, path: String, destination: OutputStream): Long {
        request(channel, "RECV", path)
        val input = DataInputStream(channel)
        val data = ByteArray(MAX_CHUNK)
        var total = 0L
        while (true) {
            val reply = reply(input)
            if (reply.id == "DONE") return total
            if (reply.id != "DATA" || reply.value !in 0..MAX_CHUNK) throw WirelessAdbStreamException("无效的 SYNC DATA 响应")
            input.readFully(data, 0, reply.value)
            destination.write(data, 0, reply.value)
            total += reply.value
        }
    }

    fun push(channel: WirelessAdbByteStreams.Channel, source: InputStream, path: String): Long {
        // Decimal S_IFREG | 0644. adbd handles device-side ownership and permission adjustments.
        request(channel, "SEND", "$path,33188")
        val input = DataInputStream(channel)
        val data = ByteArray(MAX_CHUNK)
        "DATA".toByteArray(Charsets.US_ASCII).copyInto(data)
        val packet = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var total = 0L
        while (true) {
            val size = source.read(data, HEADER, data.size - HEADER)
            if (size < 0) break
            if (size == 0) continue
            packet.putInt(4, size)
            if (!channel.write(data, HEADER + size, stopOnReply = true)) {
                reply(input)
                throw WirelessAdbStreamException("adbd 在文件结束前提前返回")
            }
            total += size
        }
        val sent = channel.write(header("DONE", (System.currentTimeMillis() / 1000).toInt()), stopOnReply = true)
        val done = reply(input)
        if (!sent || done.id != "OKAY" || done.value != 0) throw WirelessAdbStreamException("adbd 未确认接收完整文件")
        return total
    }
}

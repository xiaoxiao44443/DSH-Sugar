package io.github.muntashirakon.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WirelessAdbBinaryTest {
    private fun packet(bytes: ByteArray) = AdbProtocol.Message.parse(bytes.inputStream(), 0x01000001, 65536)
    private val sent = LinkedBlockingQueue<AdbProtocol.Message>()
    private val streams = WirelessAdbByteStreams(WirelessAdbIds(), 4096) { sent.add(packet(it)) }
    private var local = 0
    private val remote = 91

    private fun next(cmd: Int): AdbProtocol.Message {
        val msg = checkNotNull(sent.poll(4, TimeUnit.SECONDS)) { "Timed out waiting for ADB packet $cmd" }
        assertEquals(cmd, msg.command)
        return msg
    }

    private fun accept(service: String) {
        val open = next(AdbProtocol.A_OPEN)
        assertEquals(service + '\u0000', open.payload.toString(Charsets.UTF_8))
        local = open.arg0
        send(AdbProtocol.A_OKAY)
    }

    private fun send(cmd: Int, bytes: ByteArray? = null) {
        assertTrue(streams.receive(packet(AdbProtocol.generateMessage(cmd, remote, local, bytes))))
    }

    private fun transmit(bytes: ByteArray) {
        var position = 0
        var fragment = 1
        while (position < bytes.size) {
            val n = minOf(bytes.size - position, fragment)
            send(AdbProtocol.A_WRTE, bytes.copyOfRange(position, position + n))
            next(AdbProtocol.A_OKAY)
            position += n
            fragment = minOf(8191, fragment * 2)
        }
    }

    private fun frame(tag: String, payload: ByteArray = byteArrayOf()) = ByteBuffer.allocate(8 + payload.size)
        .order(ByteOrder.LITTLE_ENDIAN).put(tag.toByteArray()).putInt(payload.size).put(payload).array()
    private fun shellFrame(tag: Int, bytes: ByteArray) = ByteBuffer.allocate(5 + bytes.size)
        .order(ByteOrder.LITTLE_ENDIAN).put(tag.toByte()).putInt(bytes.size).put(bytes).array()

    @Test fun binaryExecPreservesEveryByteAndExitStatus() {
        val image = ByteArray(2 * 1024 * 1024 + 7).also(Random(29)::nextBytes)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val result = CompletableFuture.supplyAsync { streams.execOut("screencap -p", out, err, null) }
        accept("shell,v2,raw:exec </dev/null; screencap -p")
        transmit(shellFrame(1, image) + shellFrame(2, byteArrayOf(0, -1, 10)) + shellFrame(3, byteArrayOf(13)))
        send(AdbProtocol.A_CLSE)
        assertEquals(13, result.get(4, TimeUnit.SECONDS))
        assertArrayEquals(image, out.toByteArray())
        assertArrayEquals(byteArrayOf(0, -1, 10), err.toByteArray())
    }

    @Test fun pullHandlesEmptyAndMultiChunkFilesAndRemoteFailures() {
        val path = "/data/local/tmp/a b'c-.png"
        for (size in listOf(0, 3 * 65536 + 133)) {
            val source = ByteArray(size).also(Random(47)::nextBytes)
            val dest = ByteArrayOutputStream()
            val result = CompletableFuture.supplyAsync { streams.use("sync:", null) { WirelessAdbSync.pull(it, path, dest) } }
            accept("sync:")
            assertArrayEquals(frame("RECV", path.toByteArray()), next(AdbProtocol.A_WRTE).payload)
            send(AdbProtocol.A_OKAY)
            var offset = 0
            while (offset < source.size) {
                val end = minOf(source.size, offset + 65536)
                transmit(frame("DATA", source.copyOfRange(offset, end)))
                offset = end
            }
            transmit(frame("DONE"))
            assertEquals(size.toLong(), result.get(4, TimeUnit.SECONDS))
            assertArrayEquals(source, dest.toByteArray())
            next(AdbProtocol.A_CLSE)
        }
        val denied = CompletableFuture.supplyAsync {
            streams.use("sync:", null) { WirelessAdbSync.pull(it, path, ByteArrayOutputStream()) }
        }
        accept("sync:")
        next(AdbProtocol.A_WRTE)
        send(AdbProtocol.A_OKAY)
        transmit(frame("FAIL", "Permission denied".toByteArray()))
        assertTrue(assertThrows(ExecutionException::class.java) { denied.get(4, TimeUnit.SECONDS) }.cause!!.message!!.contains("Permission denied"))
        next(AdbProtocol.A_CLSE)
    }

    @Test fun pushHandlesFragmentedWritesAndEmptyFiles() {
        val path = "/data/local/tmp/a,b"
        for (size in listOf(0, 512 * 1024 + 451)) {
            val source = ByteArray(size).also(Random(12)::nextBytes)
            val result = CompletableFuture.supplyAsync {
                streams.use("sync:", null) { WirelessAdbSync.push(it, source.inputStream(), path) }
            }
            accept("sync:")
            assertArrayEquals(frame("SEND", "$path,33188".toByteArray()), next(AdbProtocol.A_WRTE).payload)
            send(AdbProtocol.A_OKAY)
            val all = ByteArrayOutputStream()
            while (true) {
                val part = next(AdbProtocol.A_WRTE).payload
                assertTrue(part.size <= 4096)
                send(AdbProtocol.A_OKAY)
                if (part.size == 8 && String(part, 0, 4) == "DONE") break
                all.write(part)
            }
            val bytes = ByteBuffer.wrap(all.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val restored = ByteArrayOutputStream()
            while (bytes.remaining() > 0) {
                assertEquals("DATA", String(ByteArray(4).also(bytes::get)))
                val length = bytes.int
                assertTrue(length in 1..65536)
                restored.write(ByteArray(length).also(bytes::get))
            }
            assertArrayEquals(source, restored.toByteArray())
            transmit(frame("OKAY"))
            assertEquals(size.toLong(), result.get(4, TimeUnit.SECONDS))
            next(AdbProtocol.A_CLSE)
        }
    }

    @Test fun disconnectAndCancellationWakeBlockedReadersWithoutAcceptingLateData() {
        val channel = CompletableFuture<WirelessAdbByteStreams.Channel>()
        fun reading() = CompletableFuture.supplyAsync {
            streams.use("sync:", null) { channel.complete(it); it.read() }
        }
        val cancelled = reading()
        accept("sync:")
        channel.get(4, TimeUnit.SECONDS).abort(InterruptedIOException("cancelled"))
        assertTrue(assertThrows(ExecutionException::class.java) { cancelled.get(4, TimeUnit.SECONDS) }.cause is InterruptedIOException)
        next(AdbProtocol.A_CLSE)
        val old = local
        val disconnected = reading()
        accept("sync:")
        assertFalse(streams.receive(packet(AdbProtocol.generateClose(remote, old))))
        streams.disconnect(IOException("disconnected"))
        assertEquals("disconnected", assertThrows(ExecutionException::class.java) { disconnected.get(4, TimeUnit.SECONDS) }.cause?.message)
    }
}

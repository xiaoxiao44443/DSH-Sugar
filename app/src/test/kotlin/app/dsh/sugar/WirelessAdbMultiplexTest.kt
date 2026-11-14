package io.github.muntashirakon.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WirelessAdbMultiplexTest {
    private val sent = LinkedBlockingQueue<AdbProtocol.Message>()
    private val ids = WirelessAdbIds()
    private val bytes = WirelessAdbByteStreams(ids, 4096, ::writePacket)
    private val shells = WirelessAdbShellStreams(ids, ::writePacket)

    private fun parse(data: ByteArray) = AdbProtocol.Message.parse(data.inputStream(), 0x01000001, 65536)
    private fun writePacket(data: ByteArray) { sent.add(parse(data)) }
    private fun next(command: Int): AdbProtocol.Message {
        val message = checkNotNull(sent.poll(4, TimeUnit.SECONDS)) { "No outgoing ADB packet for $command" }
        assertEquals(command, message.command)
        return message
    }

    private data class Stream(val local: Int, val remote: Int)
    private fun open(service: String, remote: Int): Stream {
        val message = next(AdbProtocol.A_OPEN)
        assertEquals(service + '\u0000', message.payload.toString(Charsets.UTF_8))
        return Stream(message.arg0, remote)
    }

    // Use the same shared ID allocator and dispatch order as the real TLS reader.
    private fun receive(stream: Stream, command: Int, data: ByteArray? = null) {
        val packet = parse(AdbProtocol.generateMessage(command, stream.remote, stream.local, data))
        if (!bytes.receive(packet)) shells.receive(packet)
    }

    private fun expect(stream: Stream, command: Int): AdbProtocol.Message {
        val message = next(command)
        assertEquals(stream.local, message.arg0)
        assertEquals(stream.remote, message.arg1)
        return message
    }

    private fun shellFrame(id: Int, data: ByteArray) = ByteBuffer.allocate(5 + data.size)
        .order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(data.size).put(data).array()
    private fun syncFrame(tag: String, data: ByteArray = byteArrayOf()) = ByteBuffer.allocate(8 + data.size)
        .order(ByteOrder.LITTLE_ENDIAN).put(tag.toByteArray()).putInt(data.size).put(data).array()

    @Test fun interleavedShellsKeepSeparateOutputExitCodesAndCloseEvents() {
        val one = shells.open("one")
        val a = open("shell,v2,raw:exec </dev/null; one", 74)
        val two = shells.open("two")
        val b = open("shell,v2,raw:exec </dev/null; two", 89)
        assertNotEquals(a.local, b.local)
        receive(b, AdbProtocol.A_OKAY)
        receive(a, AdbProtocol.A_OKAY)
        val firstOutput = shellFrame(1, "first".toByteArray()) + shellFrame(3, byteArrayOf(7))
        receive(a, AdbProtocol.A_WRTE, firstOutput.copyOf(3))
        expect(a, AdbProtocol.A_OKAY)
        receive(b, AdbProtocol.A_WRTE, shellFrame(2, "second".toByteArray()) + shellFrame(3, byteArrayOf(23)))
        expect(b, AdbProtocol.A_OKAY)
        receive(b, AdbProtocol.A_CLSE)
        assertEquals(WirelessAdbShellResult("", "second", 23, false), shells.await(two))
        receive(b, AdbProtocol.A_CLSE)
        receive(a, AdbProtocol.A_WRTE, firstOutput.copyOfRange(3, firstOutput.size))
        expect(a, AdbProtocol.A_OKAY)
        receive(a, AdbProtocol.A_CLSE)
        assertEquals(WirelessAdbShellResult("first", "", 7, false), shells.await(one))
    }

    @Test fun burstOf32OpenShellsRoutesEachExitCodeWhenTheyFinishInReverseOrder() {
        val calls = (0 until 32).map { shells.open("job$it") }
        val streams = calls.mapIndexed { index, call ->
            val stream = open("shell,v2,raw:exec </dev/null; job$index", 100 + index)
            assertEquals(call.id, stream.local)
            stream
        }
        assertEquals(calls.size, streams.map { it.local }.toSet().size)
        for ((index, stream) in streams.withIndex().reversed()) {
            receive(stream, AdbProtocol.A_OKAY)
            receive(stream, AdbProtocol.A_WRTE, shellFrame(3, byteArrayOf(index.toByte())))
            receive(stream, AdbProtocol.A_CLSE)
        }
        streams.asReversed().forEach { expect(it, AdbProtocol.A_OKAY) }
        calls.forEachIndexed { index, call -> assertEquals(index, shells.await(call).exitCode) }
    }

    @Test fun stalledUploadDoesNotBlockDownloadAndCancellingItLeavesDownloadUsable() {
        val handle = CompletableFuture<WirelessAdbByteStreams.Channel>()
        val upload = CompletableFuture.supplyAsync {
            bytes.use("sync:", null) { channel ->
                handle.complete(channel)
                WirelessAdbSync.push(channel, byteArrayOf(0, 4, -1).inputStream(), "/up")
            }
        }
        val a = open("sync:", 8)
        receive(a, AdbProtocol.A_OKAY)
        assertArrayEquals(syncFrame("SEND", "/up,33188".toByteArray()), expect(a, AdbProtocol.A_WRTE).payload)
        // Withhold the SEND acknowledgement. The next DATA packet cannot be written on this stream.
        val out = ByteArrayOutputStream()
        val download = CompletableFuture.supplyAsync {
            bytes.use("sync:", null) { WirelessAdbSync.pull(it, "/down", out) }
        }
        val b = open("sync:", 9)
        assertNotEquals(a.local, b.local)
        receive(b, AdbProtocol.A_OKAY)
        assertArrayEquals(syncFrame("RECV", "/down".toByteArray()), expect(b, AdbProtocol.A_WRTE).payload)
        receive(b, AdbProtocol.A_OKAY)
        val data = byteArrayOf(0, 1, -1, 98)
        receive(b, AdbProtocol.A_WRTE, syncFrame("DATA", data))
        expect(b, AdbProtocol.A_OKAY)

        handle.get(4, TimeUnit.SECONDS).abort(InterruptedIOException("cancelled upload"))
        expect(a, AdbProtocol.A_CLSE)
        val cause = assertThrows(ExecutionException::class.java) { upload.get(4, TimeUnit.SECONDS) }.cause
        assertTrue(cause is InterruptedIOException)
        receive(a, AdbProtocol.A_CLSE)
        receive(a, AdbProtocol.A_OKAY) // Late acknowledgement for the cancelled stream.
        expect(a, AdbProtocol.A_CLSE)

        receive(b, AdbProtocol.A_WRTE, syncFrame("DONE"))
        expect(b, AdbProtocol.A_OKAY)
        expect(b, AdbProtocol.A_CLSE)
        assertEquals(data.size.toLong(), download.get(4, TimeUnit.SECONDS))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun disconnectWakesEveryOpenAndOpeningStream() {
        val first = shells.open("first")
        val a = open("shell,v2,raw:exec </dev/null; first", 20)
        receive(a, AdbProtocol.A_OKAY)
        val second = shells.open("second")
        open("shell,v2,raw:exec </dev/null; second", 21)
        val reading = CompletableFuture.supplyAsync { bytes.use("sync:", null) { it.read() } }
        val b = open("sync:", 22)
        receive(b, AdbProtocol.A_OKAY)
        val opening = CompletableFuture.supplyAsync { bytes.use("sync:", null) { it.read() } }
        open("sync:", 23)
        val reason = IOException("wireless disconnected")
        bytes.disconnect(reason)
        shells.disconnect(reason)
        for (call in listOf(first, second)) {
            assertSame(reason, assertThrows(IOException::class.java) { shells.await(call) })
        }
        for (call in listOf(reading, opening)) {
            assertSame(reason, assertThrows(ExecutionException::class.java) { call.get(4, TimeUnit.SECONDS) }.cause)
        }
    }
}

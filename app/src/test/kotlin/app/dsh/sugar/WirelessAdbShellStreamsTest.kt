package io.github.muntashirakon.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WirelessAdbShellStreamsTest {
    private fun packet(bytes: ByteArray) = AdbProtocol.Message.parse(bytes.inputStream(), 0x01000001, 65536)
    private val sent = mutableListOf<AdbProtocol.Message>()
    private val streams = WirelessAdbShellStreams { sent.add(packet(it)) }

    private fun receive(command: Int, remote: Int, local: Int, bytes: ByteArray? = null) {
        streams.receive(packet(AdbProtocol.generateMessage(command, remote, local, bytes)))
    }

    private fun frame(id: Int, data: ByteArray) = ByteBuffer.allocate(5 + data.size)
        .order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(data.size).put(data).array()

    @Test fun finalWriteFollowedByCloseAndLateCloseDoesNotPoisonNextShell() {
        val packages = "package:com.example.testapp\n".repeat(4500).toByteArray()
        var previous = 0
        repeat(8) {
            val call = streams.open("pm list packages")
            if (previous != 0) {
                // adbd found the old socket gone when the host's final WRTE ACK arrived.
                receive(AdbProtocol.A_CLSE, 87, previous)
                receive(AdbProtocol.A_WRTE, 87, previous, "discard".toByteArray())
            }
            receive(AdbProtocol.A_OKAY, 87, call.id)
            val output = frame(2, "诊断".toByteArray()) + frame(1, packages) +
                frame(4, byteArrayOf()) + frame(3, byteArrayOf(193.toByte()))
            var offset = 0
            while (offset < output.size) {
                val end = minOf(output.size, offset + 8191)
                receive(AdbProtocol.A_WRTE, 87, call.id, output.copyOfRange(offset, end))
                offset = end
            }
            receive(AdbProtocol.A_CLSE, if (it % 2 == 0) 87 else 0, call.id)
            val result = streams.await(call)
            assertEquals(193, result.exitCode)
            assertEquals("诊断", result.stderr)
            assertEquals(65536 - "诊断".toByteArray().size, result.stdout.length)
            assertTrue(result.truncated)
            assertFalse(sent.any { packet -> packet.command == AdbProtocol.A_CLSE })
            previous = call.id
        }
    }

    @Test fun cancellationClosesOnlyItsStreamIncludingLateOpenConfirmation() {
        val opening = streams.open("sleep 90")
        streams.cancel(opening, "timeout")
        assertThrows(WirelessAdbStreamException::class.java) { streams.await(opening) }
        val established = streams.open("sleep 30")
        receive(AdbProtocol.A_OKAY, 7, opening.id)
        assertEquals(listOf(AdbProtocol.A_CLSE, opening.id, 7), sent.last().let { listOf(it.command, it.arg0, it.arg1) })
        receive(AdbProtocol.A_OKAY, 8, established.id)
        streams.cancel(established, "timeout")
        assertThrows(WirelessAdbStreamException::class.java) { streams.await(established) }
        assertEquals(listOf(AdbProtocol.A_CLSE, established.id, 8), sent.last().let { listOf(it.command, it.arg0, it.arg1) })
        val next = streams.open("true")
        receive(AdbProtocol.A_CLSE, 8, established.id)
        receive(AdbProtocol.A_OKAY, 9, next.id)
        receive(AdbProtocol.A_WRTE, 9, next.id, frame(3, byteArrayOf(0)))
        receive(AdbProtocol.A_CLSE, 9, next.id)
        assertEquals(0, streams.await(next).exitCode)
    }

    @Test fun remoteServiceFailuresLeaveTransportUsable() {
        val denied = streams.open("id")
        receive(AdbProtocol.A_CLSE, 0, denied.id)
        assertThrows(WirelessAdbStreamException::class.java) { streams.await(denied) }
        val incomplete = streams.open("id")
        receive(AdbProtocol.A_OKAY, 18, incomplete.id)
        receive(AdbProtocol.A_WRTE, 18, incomplete.id, byteArrayOf(1, 12))
        receive(AdbProtocol.A_CLSE, 18, incomplete.id)
        assertThrows(WirelessAdbStreamException::class.java) { streams.await(incomplete) }
        val next = streams.open("true")
        receive(AdbProtocol.A_OKAY, 19, next.id)
        receive(AdbProtocol.A_WRTE, 19, next.id, frame(3, byteArrayOf(0)))
        receive(AdbProtocol.A_CLSE, 19, next.id)
        assertEquals(0, streams.await(next).exitCode)
    }
}

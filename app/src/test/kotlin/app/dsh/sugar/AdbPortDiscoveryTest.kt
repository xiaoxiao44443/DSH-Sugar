package app.dsh.sugar

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AdbPortDiscoveryTest {
    @Test fun allDistinctConnectCandidatesReachAuthenticationAndCanReappear() {
        val ports = AdbPortAnnouncements(connecting = true)
        val first = 41001
        val second = 41002
        assertEquals(listOf(first), ports.update(first, emptyList()))
        assertEquals(listOf(second), ports.update(first, listOf(first, second, second)))
        assertEquals(emptyList<Int>(), ports.update(null, listOf(second, first)))
        assertEquals(emptyList<Int>(), ports.update(first, listOf(first)))
        assertEquals(listOf(second), ports.update(first, listOf(second)))
        assertEquals(listOf(null), ports.update(null, emptyList()))
        assertFalse(ports.hasCandidates)
        assertEquals(listOf(second, first), ports.update(null, listOf(second, first)))
        val pairing = AdbPortAnnouncements(connecting = false)
        assertEquals(listOf(first), pairing.update(null, listOf(first, second)))
        assertEquals(emptyList<Int>(), pairing.update(null, listOf(first, second)))
        assertEquals(listOf(second), pairing.update(null, listOf(second)))
    }
    @Test fun propertyCanBecomeAvailableAfterDebuggingIsEnabled() {
        val attempted = mutableListOf<Int>()
        val ports = listOf(null, "", "permission denied", "-1", "65536", "99999999999999", "5555", "41417\n")
        val results = ports.map { raw -> AdbDiscoveryCandidates.verifiedPropertyPort(raw) {
            attempted.add(it)
            it == 41417
        } }
        assertEquals(listOf(5555, 41417), attempted)
        assertEquals(listOf(41417), results.filterNotNull())
    }

    @Test fun localDnsServiceUsesAllAddressesAndChecksTheExactType() {
        val remote = InetAddress.getByAddress(byteArrayOf(10, 42, 0, 9))
        val local = InetAddress.getByAddress(byteArrayOf(10, 42, 0, 3))
        assertTrue(AdbDiscoveryCandidates.isLocal(listOf(remote, local)) { it == local })
        assertFalse(AdbDiscoveryCandidates.isLocal(listOf(remote)) { it == local })
        for (type in listOf("_adb-tls-connect._tcp.", "._adb-tls-connect._tcp", "_ADB-TLS-CONNECT._TCP.LOCAL.")) {
            assertTrue(AdbDiscoveryCandidates.matches(type, AdbDiscoveryCandidates.CONNECT))
        }
        assertFalse(AdbDiscoveryCandidates.matches("_adb-tls-connect._tcp.evil", AdbDiscoveryCandidates.CONNECT))
    }

    @Test fun staleOrPlaintextCandidateIsSkippedAndOnlyTheLiveTlsCandidateReported() {
        fun simulate(reply: String): Boolean {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            ServerSocket(0, 1, loopback).use { server ->
                server.soTimeout = 2500
                val peer = CompletableFuture.supplyAsync {
                    server.accept().use { socket ->
                        socket.soTimeout = 2500
                        val input = DataInputStream(socket.getInputStream())
                        val hdr = ByteArray(24).also(input::readFully)
                        assertEquals("CNXN", String(hdr, 0, 4, Charsets.US_ASCII))
                        val length = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
                        assertTrue(length in 1..128)
                        val banner = ByteArray(length).also(input::readFully)
                        assertTrue(banner.toString(Charsets.UTF_8).startsWith("host::"))
                        val cmd = ByteBuffer.wrap(reply.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
                        val response = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(cmd).putInt(0x01000000).putInt(0).putInt(0).putInt(0).putInt(cmd.inv()).array()
                        socket.getOutputStream().write(response)
                        // Probing never sends a certificate, public key, pairing code or command.
                        input.read()
                    }
                }
                val result = AdbDiscoveryCandidates.advertisedPort(server.localPort, local = true, connecting = true)
                assertEquals(-1, peer.get(3, TimeUnit.SECONDS))
                return result == server.localPort
            }
        }
        assertFalse(simulate("CNXN"))
        assertFalse(simulate("AUTH"))
        assertTrue(simulate("STLS"))
    }

    @Test fun neverProbeLanPeersOrPairingWindows() {
        assertNull(AdbDiscoveryCandidates.advertisedPort(30000, local = false, connecting = true) { error("remote") })
        assertEquals(30001, AdbDiscoveryCandidates.advertisedPort(30001, local = true, connecting = false) { error("pairing") })
    }
}

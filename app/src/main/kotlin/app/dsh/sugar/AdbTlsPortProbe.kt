package io.github.muntashirakon.adb

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Verify an advertised port without offering keys, touching a pairing window or opening a shell. */
internal object AdbTlsPortProbe {
    fun accepts(port: Int): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 450)
                socket.soTimeout = 750
                val out = socket.getOutputStream()
                out.write(AdbProtocol.generateMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION_SKIP_CHECKSUM,
                    65536, "host::features=shell_v2\u0000".toByteArray()))
                out.flush()
                val reply = AdbProtocol.Message.parse(socket.getInputStream(), AdbProtocol.A_VERSION_SKIP_CHECKSUM, 0)
                reply.command == AdbProtocol.A_STLS && reply.arg0 >= AdbProtocol.A_STLS_VERSION_MIN && reply.arg1 == 0
            }
        } catch (_: IOException) { false } catch (_: SecurityException) { false }
    }
}

package app.dsh.sugar

import io.github.muntashirakon.adb.AdbTlsPortProbe
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

internal object AdbDiscoveryCandidates {
    const val CONNECT = "_adb-tls-connect._tcp"

    fun matches(actual: String?, expected: String): Boolean {
        return actual?.trim('.')?.lowercase()?.removeSuffix(".local") == expected.lowercase()
    }

    fun isLocal(addresses: List<InetAddress>, owns: (InetAddress) -> Boolean): Boolean =
        addresses.any { it.isLoopbackAddress || owns(it) }

    fun advertisedPort(port: Int, local: Boolean, connecting: Boolean, probe: (Int) -> Boolean = AdbTlsPortProbe::accepts): Int? =
        port.takeIf { local && it in 1..65535 && (!connecting || probe(it)) }

    fun verifiedPropertyPort(value: String?, probe: (Int) -> Boolean = AdbTlsPortProbe::accepts): Int? {
        val parsed = value?.trim()?.takeIf { it.length in 1..5 && it.all { c -> c in '0'..'9' } }?.toInt()
        return parsed?.takeIf { it in 1..65535 && probe(it) }
    }

    /** adbd sets this connection-only property. Some OEM policies deny ordinary apps read access. */
    fun readSystemPort(): String? {
        val process = try {
            ProcessBuilder("/system/bin/getprop", "service.adb.tls.port").redirectErrorStream(true).start()
        } catch (_: IOException) { return null } catch (_: SecurityException) { return null }
        return try {
            if (!process.waitFor(750, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
            else process.inputStream.bufferedReader().use { it.readText() }
        } catch (_: IOException) { null } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            process.destroyForcibly()
        }
    }
}

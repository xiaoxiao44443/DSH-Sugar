package app.dsh.sugar.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.sucrune.probe.RuntimeClient
import com.sucrune.probe.RuntimeExceptionWithCode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** Android keeps TZif records in a packed file, while Linux libc looks up individual files. */
internal object GuestTimeZone {
    private const val marker = "/opt/sugar/device-timezone"
    private const val tag = "SugarTimeZone"
    private val validId = Regex("[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*")
    private val environments = mutableMapOf<String, String>()
    private var observing = false

    private data class Zone(val id: String, val rules: ByteArray)
    private var cached: Zone? = null

    @Synchronized fun prepare(context: Context, client: RuntimeClient, envId: String): String {
        val app = context.applicationContext
        if (!observing) {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
                    val pending = goAsync()
                    thread(name = "sugar-timezone") {
                        try { refresh(app, client) } finally { pending.finish() }
                    }
                }
            }, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
            observing = true
        }
        val zone = current(app)
        if (environments[envId] != zone.id) {
            install(app, client, envId, zone)
            environments[envId] = zone.id
        }
        return zone.id
    }

    @Synchronized fun forget(envId: String) { environments.remove(envId) }

    @Synchronized private fun refresh(context: Context, client: RuntimeClient) {
        val zone = current(context)
        for (entry in environments.entries) {
            if (entry.value == zone.id) continue
            try {
                install(context, client, entry.key, zone)
                entry.setValue(zone.id)
            } catch (error: Exception) {
                Log.w(tag, "Could not update guest time zone for ${entry.key}", error)
            }
        }
    }

    private fun current(context: Context): Zone {
        val phone = TimeZone.getDefault()
        // Fixed offsets can include a colon, which the manager excludes from guest paths.
        // ICU accepts the equivalent compact GMT+0530 spelling as well.
        val id = phone.id.replace(":", "").takeIf(validId::matches) ?: "UTC"
        return cached?.takeIf { it.id == id } ?: Zone(id, rules(context, id) ?: fixedRules(phone)).also { cached = it }
    }

    private fun install(context: Context, client: RuntimeClient, envId: String, zone: Zone) {
        val previous = read(client, envId, marker)?.decodeToString()?.trim()?.takeIf(validId::matches)
        val path = "/usr/share/zoneinfo/${zone.id}"
        val previousRules = previous?.let { id ->
            if (id == zone.id) zone.rules else rules(context, id) ?: fixedRules(TimeZone.getTimeZone(id))
        }
        try {
            client.makeDirectory(envId, path.substringBeforeLast('/'))
            writeDefault(client, envId, path, zone.rules)
        } catch (error: RuntimeExceptionWithCode) {
            // A user-installed zoneinfo directory can itself be a symlink.
            if (error.code != "unsafe_path") throw error
        }
        writeDefault(client, envId, "/etc/localtime", zone.rules, previousRules)
        if (previous != zone.id) {
            client.makeDirectory(envId, "/opt/sugar")
            client.writeFile(envId, marker, "${zone.id}\n".toByteArray())
        }
    }

    private fun read(client: RuntimeClient, envId: String, path: String): ByteArray? =
        try { client.readFile(envId, path) }
        catch (error: RuntimeExceptionWithCode) {
            if (error.code != "not_found") throw error
            null
        }

    // A distribution or the user may already maintain its own copy or symlink.
    private fun writeDefault(client: RuntimeClient, envId: String, path: String, bytes: ByteArray, previous: ByteArray? = null) {
        try {
            val existing = read(client, envId, path)
            if (existing == null || (previous != null && existing.contentEquals(previous) && !existing.contentEquals(bytes))) {
                client.writeFile(envId, path, bytes)
            }
        } catch (error: RuntimeExceptionWithCode) {
            if (error.code != "unsafe_path" && error.code != "too_large") throw error
        }
    }

    private fun rules(context: Context, id: String): ByteArray? {
        for (path in listOf("/apex/com.android.tzdata/etc/tz/tzdata", "/system/usr/share/zoneinfo/tzdata")) {
            try { androidRules(path, id)?.let { return it } } catch (_: IOException) { }
        }
        // Fallback for vendors that restrict direct access. Go lib/time/zoneinfo.zip contains public-domain IANA TZif data.
        ZipInputStream(context.assets.open("guest/zoneinfo.zip")).use { input ->
            while (true) {
                val entry = input.nextEntry ?: return null
                if (entry.name == id) return input.readBytes()
            }
        }
    }

    private fun androidRules(path: String, id: String): ByteArray? {
        if (!File(path).canRead()) return null
        RandomAccessFile(path, "r").use { file ->
            val header = ByteArray(12).also(file::readFully)
            if (!header.decodeToString().startsWith("tzdata")) return null
            val index = file.readInt()
            val data = file.readInt()
            // Bionic's sorted index entries contain a 40-byte NUL-padded ID, offset, length, and raw UTC offset.
            if (index < 24 || data < index || data.toLong() > file.length() || (data - index) % 52 != 0) return null
            var low = 0
            var high = (data - index) / 52 - 1
            val key = ByteArray(40)
            while (low <= high) {
                val mid = (low + high) ushr 1
                file.seek(index + mid * 52L)
                file.readFully(key)
                val comparison = key.decodeToString().trimEnd('\u0000').compareTo(id)
                if (comparison < 0) low = mid + 1
                else if (comparison > 0) high = mid - 1
                else {
                    val offset = file.readInt()
                    val length = file.readInt()
                    if (offset < 0 || length !in 44..1_048_576 || data.toLong() + offset + length > file.length()) return null
                    file.seek(data.toLong() + offset)
                    return ByteArray(length).also(file::readFully)
                }
            }
            return null
        }
    }

    // Android also accepts fixed offsets (e.g. GMT+05:30) which have no IANA file.
    private fun fixedRules(zone: TimeZone): ByteArray {
        val abbreviation = (zone.id + "\u0000").toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(50 + abbreviation.size).apply {
            put("TZif".toByteArray(Charsets.US_ASCII))
            position(36)
            putInt(1)
            putInt(abbreviation.size)
            putInt(zone.getOffset(System.currentTimeMillis()) / 1000)
            put(0)
            put(0)
            put(abbreviation)
        }.array()
    }
}

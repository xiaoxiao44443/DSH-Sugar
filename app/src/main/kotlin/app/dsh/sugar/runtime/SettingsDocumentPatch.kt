package app.dsh.sugar.runtime

import android.util.Log
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.sucrune.probe.RuntimeClient
import java.security.MessageDigest

/** Adapt the pinned general-settings plugin when it is installed, including upgraded devices. */
internal object SettingsDocumentPatch {
    private const val directory = "/opt/dsh/node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib"
    private const val originalSize = 89954L
    private const val originalDigest = "8c97e21e236706cdd0185e5a1a75aeb17cc26352b50e0cec4bfd0a8583b51055"
    private const val delegate = "onClick:()=>{controller.open()},"
    private const val adapted = "onClick:()=>{if(typeof window.__dshSugarOpenProfile===\"function\")window.__dshSugarOpenProfile();else controller.open()},\"data-sugar-open-settings\":true,"

    fun prepare(client: RuntimeClient, id: String) {
        recoverEditorLock(client, id)
        try {
            val entries = client.listFiles(id, directory).getJSONArray("entries")
            val file = (0 until entries.length()).map { entries.getJSONObject(it) }
                .firstOrNull { it.optString("name") == "client.js" } ?: return
            if (file.optLong("size") != originalSize) return
            val path = "$directory/client.js"
            val content = client.readFile(id, path)
            if (sha256(content) != originalDigest) return
            val source = content.toString(Charsets.UTF_8)
            check(source.indexOf(delegate) >= 0 && source.indexOf(delegate) == source.lastIndexOf(delegate))
            client.writeFile(id, path, source.replace(delegate, adapted).toByteArray())
        } catch (_: Exception) {
            Log.w("DSHSugar", "Could not adapt the settings document action")
        }
    }

    // Sucrune workers only see their own process tree. Only the Android host
    // can prove a marker's isolated worker has exited, including after an app crash.
    @Synchronized
    fun recoverEditorLock(client: RuntimeClient, id: String, recovery: Boolean = false) {
        val lock = if (recovery) RecoveryProfileDocument.PATH.substringBeforeLast('/') + "/package.json.lock"
            else "/home/sugar/.dsh/profiles/web/package.json.lock"
        try {
            val record = client.readFile(id, lock).toString(Charsets.US_ASCII)
            val match = Regex("""dsh-sugar-editor:([1-9][0-9]{0,9})\n""").matchEntire(record) ?: return
            val pid = match.groupValues[1].toIntOrNull() ?: return
            try {
                Os.kill(pid, 0)
                return
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESRCH) return
            }
            val checkAndRemove = """
                const fs=require('node:fs'), p=process.argv[1], owner=process.argv[2];
                if(fs.lstatSync(p).isFile() && fs.readFileSync(p,'utf8')===owner) fs.unlinkSync(p)
            """.trimIndent()
            val execution = client.executePath(id, "/usr/bin/node", listOf("-e", checkAndRemove, lock, record),
                env = listOf("LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu",
                    "SUCRUNE_MEMORY_POLICY=linux-jit") + (if (recovery) listOf("SUCRUNE_WRITE_SCOPE=workspace-write") else emptyList()),
                timeoutMs = 10_000)
            client.waitFor(execution, 15_000)
        } catch (_: Exception) {
            // No profile or no lock is normal. Unknown, active and unreadable locks stay in place.
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

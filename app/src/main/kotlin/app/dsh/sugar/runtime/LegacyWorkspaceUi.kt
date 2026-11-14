package app.dsh.sugar.runtime

import android.content.Context
import com.sucrune.probe.RuntimeClient
import com.sucrune.probe.RuntimeExceptionWithCode
import java.util.zip.ZipInputStream

/** Old Android builds patched these upstream modules. Restore only known Sugar edits on upgrade. */
internal object LegacyWorkspaceUi {
    private data class Module(val name: String, val upstream: String, val old: Map<Int, String>) {
        val entry = "opt/dsh/node_modules/@deepseek-ai/dsh-client-ui-$name/lib/client.js"
    }

    private val modules = listOf(
        Module("workspace", "3ff4116dc91b7dc5cc57e7672e0d7935f992b339be3444a7a72992df218765ba", mapOf(
            288127 to "81da7b96f8145ca708b41f39bfe75291cf82586fe09859488dbc0407d879e26a",
            288726 to "bd6d4073ff029d23451e139709dc5cac11a09ce5b4637bf410a9e8d631edea7e",
            288368 to "06115dac1f04ebb060f67e5dc518e85df46b4f5a5a6f71da6727f11cc82bae13"
        )),
        Module("directory-picker-browse", "e90178a60fe35c9982bf75e8aa7ac57061eabcf21928a65e6bf4043930a567a5", mapOf(
            58234 to "0c24fd0089e346d7265a646665638ab2f80b2e068c96587a148d575d9b42f3a0"
        )),
        Module("plugin-manager", "1f3d4f47ca3fc521b24237fe0ce2d5607c99ba974bea3a53933829d2634792fb", mapOf(
            439281 to "113b5d116d5d28eefaca48c3d5648893e77678a2fa19afb8d6746ca6950ef463",
            439406 to "b692babd83bf89a671430431236a500c2cff7820288b6decb527f12db3a25fb5"
        ))
    )

    @Suppress("DEPRECATION") // Also available below API 33.
    fun restore(context: Context, client: RuntimeClient, env: String) {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val receipt = context.getSharedPreferences("sugar-legacy-workspace-ui", Context.MODE_PRIVATE)
        if (receipt.getLong(env, -1) == installed) return
        val pending = mutableMapOf<String, Module>()
        for (module in modules) {
            val path = "/" + module.entry
            val entries = try {
                client.listFiles(env, path.substringBeforeLast('/')).getJSONArray("entries")
            } catch (e: RuntimeExceptionWithCode) {
                if (e.code == "not_found") continue
                throw e
            }
            val file = (0 until entries.length()).map(entries::getJSONObject).find { it.optString("name") == "client.js" }
            val previous = module.old[file?.optInt("size") ?: -1] ?: continue
            if (SettingsDocumentPatch.sha256(client.readFile(env, path)) == previous) pending[module.entry] = module
        }
        if (pending.isNotEmpty()) ZipInputStream(context.assets.open("guest/rootfs.zip").buffered()).use { archive ->
            while (pending.isNotEmpty()) {
                val entry = archive.nextEntry ?: error("安装包缺少原版 DSH 界面文件。")
                val module = pending.remove(entry.name) ?: continue
                val original = archive.readBytes()
                check(SettingsDocumentPatch.sha256(original) == module.upstream) { "原版 DSH 界面文件校验失败。" }
                client.writeFile(env, "/" + module.entry, original)
            }
        }
        receipt.edit().putLong(env, installed).apply()
    }
}

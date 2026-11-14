package app.dsh.sugar.runtime

import android.content.Context
import app.dsh.sugar.R
import app.dsh.sugar.sugarString
import com.sucrune.probe.RuntimeClient

internal object RecoveryPolicy {
    fun install(context: Context, client: RuntimeClient, id: String, builtin: Boolean): String {
        val plugin = "dsh-sugar-recovery"
        val target = "/opt/sugar/$plugin"
        client.makeDirectory(id, target)
        for (file in context.assets.list("guest/$plugin").orEmpty()) {
            context.assets.open("guest/$plugin/$file").use { client.writeFile(id, "$target/$file", it.readBytes()) }
        }
        context.assets.open("recovery/preset-registry.mjs").use {
            client.writeFile(id, "$target/preset-registry.mjs", it.readBytes())
        }
        val variant = if (builtin) {
            "You are running Sugar's bundled known-good DSH. Use the running copy as a reference when checking the faulty installation."
        } else {
            "You are running a detached copy of the installed DSH with a clean temporary profile. Start by checking the original configuration."
        }
        val name = context.sugarString(R.string.recovery_preset_name)
        return context.assets.open("recovery/recovery.patch.yml").bufferedReader().use { it.readText() }
            .replace("PRESET_NAME", name.replace("'", "''"))
            .replace("RECOVERY_VARIANT", variant)
    }
}

package app.dsh.sugar

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal object DesktopPets {
    val states = listOf("idle", "phone", "coding", "thinking", "asking", "working")
    val sizesRange = 64..192
    private const val defaultPack = "sugar"
    private val packId = Regex("[a-z0-9][a-z0-9_-]*")
    private var catalog: List<Pack>? = null

    data class Pack(val id: String, private val names: Map<String, String>, val bubbleRes: Int, val bubbleBelowRes: Int) {
        fun name(context: Context): String =
            names[SugarLanguage.read(context)] ?: names["en"] ?: id
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("desktop-pet", Context.MODE_PRIVATE)

    @Synchronized
    fun packs(context: Context): List<Pack> {
        catalog?.let { return it }
        val assets = context.assets
        val order = compareBy<String> { it != defaultPack }.thenBy { it }
        val packs = assets.list("pets").orEmpty().sortedWith(order).mapNotNull { id ->
            if (!packId.matches(id)) return@mapNotNull null
            try {
                val files = assets.list("pets/$id").orEmpty().toSet()
                if ("manifest.json" !in files || states.any { "$it.webp" !in files }) {
                    Log.w("DesktopPets", "Incomplete desktop pet pack: $id")
                    return@mapNotNull null
                }
                val manifest = assets.open("pets/$id/manifest.json").bufferedReader().use { it.readText() }
                val labels = JSONObject(manifest).getJSONObject("names")
                val names = labels.keys().asSequence().mapNotNull { language ->
                    (labels.opt(language) as? String)?.trim()?.takeIf(String::isNotEmpty)?.let { language to it }
                }.toMap()
                val base = "pet_bubble_" + id.replace('-', '_')
                fun frame(name: String, fallback: Int) =
                    context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 } ?: fallback
                Pack(id, names, frame(base, R.drawable.sugar_pet_bubble),
                    frame("${base}_below", R.drawable.sugar_pet_bubble_below))
            } catch (e: IOException) {
                Log.w("DesktopPets", "Cannot read desktop pet pack: $id", e)
                null
            } catch (e: JSONException) {
                Log.w("DesktopPets", "Invalid desktop pet manifest: $id", e)
                null
            }
        }
        catalog = packs
        return packs
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun openConversationOnBubbleTap(context: Context): Boolean =
        prefs(context).getBoolean("openConversationOnBubbleTap", false)

    fun setOpenConversationOnBubbleTap(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("openConversationOnBubbleTap", enabled).apply()
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("enabled", value).apply()
    }

    fun selected(context: Context): String {
        val packs = packs(context)
        val saved = prefs(context).getString("selected", defaultPack)
        return packs.find { it.id == saved }?.id ?: packs.find { it.id == defaultPack }?.id
            ?: packs.firstOrNull()?.id.orEmpty()
    }

    fun select(context: Context, petId: String) {
        if (packs(context).any { it.id == petId }) prefs(context).edit().putString("selected", petId).apply()
    }

    fun size(context: Context): Int = prefs(context).getInt("size", 112).coerceIn(sizesRange)

    fun setSize(context: Context, value: Int) {
        prefs(context).edit().putInt("size", value.coerceIn(sizesRange)).apply()
    }

    fun drawable(context: Context, petId: String, state: String): AnimatedImageDrawable? {
        if (state !in states || packs(context).none { it.id == petId }) return null
        return try {
            val source = ImageDecoder.createSource(context.assets, "pets/$petId/$state.webp")
            (ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            }
        } catch (e: IOException) {
            Log.w("DesktopPets", "Cannot decode $petId/$state", e)
            null
        }
    }
}

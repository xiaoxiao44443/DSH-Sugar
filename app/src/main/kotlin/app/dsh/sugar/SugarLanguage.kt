package app.dsh.sugar

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** A private resource context keeps Sugar's preference independent of the embedded DSH WebView. */
internal object SugarLanguage {
    private val supported = setOf("zh", "en", "ja")
    private fun preferences(context: Context) = context.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE)
    fun read(context: Context): String = preferences(context).getString("language", null)
        ?.takeIf { it in supported } ?: "zh"

    fun set(context: Context, language: String): Boolean {
        if (language !in supported) return false
        preferences(context).edit().putString("language", language).apply()
        return true
    }

    private var cachedTag: String? = null
    private lateinit var cachedResources: Resources

    @Synchronized
    fun resources(context: Context): Resources {
        val tag = read(context)
        if (cachedTag != tag) {
            val override = Configuration().apply { setLocale(Locale.forLanguageTag(tag)) }
            cachedResources = context.applicationContext.createConfigurationContext(override).resources
            cachedTag = tag
        }
        return cachedResources
    }
}

internal fun Context.sugarString(id: Int, vararg args: Any): String =
    SugarLanguage.resources(this).getString(id, *args)

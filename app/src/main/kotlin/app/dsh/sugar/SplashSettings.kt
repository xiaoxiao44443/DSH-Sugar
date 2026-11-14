package app.dsh.sugar

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal data class SplashPosition(val x: Double, val y: Double) {
    fun json() = JSONObject().put("x", x).put("y", y)

    companion object {
        fun parse(value: JSONObject?): SplashPosition? {
            val x = (value?.opt("x") as? Number)?.toDouble() ?: return null
            val y = (value.opt("y") as? Number)?.toDouble() ?: return null
            return if (x in 0.0..100.0 && y in 0.0..100.0) SplashPosition(x, y) else null
        }
    }
}

internal data class SplashSettings(
    val portraitPercent: Int = 65,
    val landscapePercent: Int = 55,
    val portraitFit: String = "contain",
    val landscapeFit: String = "contain",
    val showProgress: Boolean = true,
    val loadingText: String,
    val durationMs: Int = 0,
    val randomExit: Boolean = false,
    val backgroundColor: String = "#FFFFFF",
    val imageName: String? = null,
    val portraitImagePosition: SplashPosition? = null,
    val portraitTextPosition: SplashPosition? = null,
    val landscapeImagePosition: SplashPosition? = null,
    val landscapeTextPosition: SplashPosition? = null,
) {
    fun json() = JSONObject()
        .put("portraitPercent", portraitPercent).put("landscapePercent", landscapePercent)
        .put("portraitFit", portraitFit).put("landscapeFit", landscapeFit)
        .put("showProgress", showProgress).put("loadingText", loadingText)
        .put("durationMs", durationMs).put("randomExit", randomExit)
        .put("backgroundColor", backgroundColor).put("imageName", imageName ?: JSONObject.NULL)
        .put("portraitImagePosition", portraitImagePosition?.json() ?: JSONObject.NULL)
        .put("portraitTextPosition", portraitTextPosition?.json() ?: JSONObject.NULL)
        .put("landscapeImagePosition", landscapeImagePosition?.json() ?: JSONObject.NULL)
        .put("landscapeTextPosition", landscapeTextPosition?.json() ?: JSONObject.NULL)

    companion object {
        private val hex = Regex("#[0-9A-Fa-f]{6}")
        internal fun preferences(context: Context) = context.getSharedPreferences("startup-screen", Context.MODE_PRIVATE)
        internal fun imageDirectory(context: Context) = File(context.filesDir, "startup-images")

        fun customImage(context: Context): File? {
            val id = preferences(context).getString("imageFile", null) ?: return null
            if (!id.startsWith("splash-") || '/' in id || '\\' in id) return null
            return File(imageDirectory(context), id).takeIf { it.isFile }
        }

        fun imageMime(file: File): String? = SplashImageFormat.mime(file)

        fun read(context: Context): SplashSettings {
            val defaults = SplashSettings(loadingText = context.sugarString(R.string.splash_loading))
            val p = preferences(context)
            fun position(key: String): SplashPosition? {
                val raw = p.getString(key, null) ?: return null
                return try { SplashPosition.parse(JSONObject(raw)) } catch (_: org.json.JSONException) { null }
            }
            return SplashSettings(
                portraitPercent = p.getInt("portraitPercent", defaults.portraitPercent),
                landscapePercent = p.getInt("landscapePercent", defaults.landscapePercent),
                portraitImagePosition = position("portraitImagePosition"),
                portraitTextPosition = position("portraitTextPosition"),
                landscapeImagePosition = position("landscapeImagePosition"),
                landscapeTextPosition = position("landscapeTextPosition"),
                portraitFit = p.getString("portraitFit", null) ?: defaults.portraitFit,
                landscapeFit = p.getString("landscapeFit", null) ?: defaults.landscapeFit,
                showProgress = p.getBoolean("showProgress", defaults.showProgress),
                loadingText = p.getString("loadingText", null) ?: defaults.loadingText,
                durationMs = p.getInt("durationMs", defaults.durationMs),
                randomExit = p.getBoolean("randomExit", defaults.randomExit),
                backgroundColor = p.getString("backgroundColor", null) ?: defaults.backgroundColor,
                imageName = if (customImage(context) == null) null else p.getString("imageName", context.sugarString(R.string.splash_custom_image)),
            )
        }

        // Only the picker can change the selected image. Validate the entire patch before applying it.
        fun update(context: Context, changes: JSONObject): Boolean {
            val editor = preferences(context).edit()
            for (key in changes.keys()) {
                val value = changes.opt(key)
                when (key) {
                    "portraitPercent", "landscapePercent", "durationMs" -> {
                        val range = if (key == "durationMs") 0..60000 else 10..100
                        if (value !is Number || value.toDouble() != value.toInt().toDouble() || value.toInt() !in range) return false
                        editor.putInt(key, value.toInt())
                    }
                    "portraitFit", "landscapeFit" -> {
                        if (value !is String || (value != "contain" && value != "cover")) return false
                        editor.putString(key, value)
                    }
                    "showProgress", "randomExit" -> {
                        if (value !is Boolean) return false
                        editor.putBoolean(key, value)
                    }
                    "portraitImagePosition", "portraitTextPosition", "landscapeImagePosition", "landscapeTextPosition" -> {
                        if (value === JSONObject.NULL) editor.remove(key)
                        else {
                            val pos = SplashPosition.parse(value as? JSONObject) ?: return false
                            editor.putString(key, pos.json().toString())
                        }
                    }
                    "loadingText" -> {
                        if (value !is String || value.length > 160) return false
                        editor.putString(key, value)
                    }
                    "backgroundColor" -> {
                        if (value !is String || !hex.matches(value)) return false
                        editor.putString(key, value.uppercase(Locale.ROOT))
                    }
                    else -> return false
                }
            }
            editor.apply()
            return true
        }
    }
}

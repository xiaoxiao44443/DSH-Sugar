package app.dsh.sugar

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import kotlin.math.roundToInt

/** The last page palette remains available while Sugar's own Activity is in the background. */
internal object SugarOverlayPalette {
    data class Palette(val surface: Int, val ink: Int, val opacity: Double, val blurDp: Double)

    private val hex = Regex("#[0-9a-fA-F]{6}")
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("overlay-palette", Context.MODE_PRIVATE)
    private fun edgePreferences(context: Context) =
        context.applicationContext.getSharedPreferences("edge-navigation", Context.MODE_PRIVATE)

    fun read(context: Context): Palette {
        val prefs = preferences(context)
        val edge = edgePreferences(context)
        val surface = prefs.getInt("surface", context.getColor(R.color.sugar_paper))
        val ink = prefs.getInt("ink", context.getColor(R.color.sugar_ink))
        val transparency = edge.getInt("transparency", 16).coerceIn(0, 100)
        val defaultOpacity = (82 + 10 * (1 - transparency / 100.0)).roundToInt() / 100.0
        val defaultBlur = SugarGlassMaterial(surface, Color.luminance(surface) < .36).modalBlurDp.roundToInt().toDouble()
        val opacity = Double.fromBits(prefs.getLong("opacity", defaultOpacity.toBits()))
            .takeIf { it in 0.0..1.0 } ?: defaultOpacity
        val blur = Double.fromBits(prefs.getLong("blurDp", defaultBlur.toBits()))
            .takeIf { it in 0.0..32.0 } ?: defaultBlur
        return Palette(surface, ink, opacity, if (edge.getBoolean("blur", true)) blur else 0.0)
    }

    fun update(context: Context, surface: String, ink: String, opacity: Double, blurDp: Double) {
        if (!hex.matches(surface) || !hex.matches(ink) || opacity !in 0.0..1.0 || blurDp !in 0.0..32.0) return
        val next = Palette(Color.parseColor(surface), Color.parseColor(ink), opacity, blurDp)
        val prefs = preferences(context)
        if (prefs.getInt("surface", 0) == next.surface && prefs.getInt("ink", 0) == next.ink &&
            prefs.getLong("opacity", Long.MIN_VALUE) == opacity.toBits() &&
            prefs.getLong("blurDp", Long.MIN_VALUE) == blurDp.toBits()) return
        prefs.edit().putInt("surface", next.surface).putInt("ink", next.ink)
            .putLong("opacity", opacity.toBits()).putLong("blurDp", blurDp.toBits()).apply()
    }

    /** The returned function retains and unregisters both listeners; call it with the overlay's owner. */
    fun listen(context: Context, onChanged: () -> Unit): () -> Unit {
        val app = context.applicationContext
        val prefs = preferences(app)
        val edge = edgePreferences(app)
        var last = read(app)
        var listening = true
        fun changed() {
            if (!listening) return
            val next = read(app)
            if (next == last) return
            last = next
            onChanged()
        }
        val paletteListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        val edgeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "blur" || key == "transparency") changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(paletteListener)
        edge.registerOnSharedPreferenceChangeListener(edgeListener)
        return {
            listening = false
            prefs.unregisterOnSharedPreferenceChangeListener(paletteListener)
            edge.unregisterOnSharedPreferenceChangeListener(edgeListener)
        }
    }
}

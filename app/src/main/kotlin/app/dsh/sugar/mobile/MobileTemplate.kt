package app.dsh.sugar.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

private val pngMagic = byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)

internal object MobileTemplate {
    private class Pixels(val rgb: IntArray,val width: Int,val height: Int)

    @Synchronized fun find(image: JSONObject, args: JSONObject): JSONObject = try {
        val template = pixels(args.getString("template"),true)
        val screen = pixels(image.getString("data"),false)
        require(screen.width == image.getInt("width") && screen.height == image.getInt("height")) {
            "Screenshot metadata does not match its pixels."
        }
        val result = MobileIconMatcher.find(screen.rgb,screen.width,screen.height,template.rgb,template.width,template.height,
            args.optInt("tolerance",12),args.optInt("max_matches",8))
        val matches = JSONArray()
        for (hit in result.hits) {
            matches.put(JSONObject().put("x",hit.left + template.width / 2).put("y",hit.top + template.height / 2)
                .put("bounds",JSONArray(listOf(hit.left,hit.top,hit.left+template.width,hit.top+template.height))))
        }
        JSONObject().put("ok",true).put("status",result.status).put("ambiguous",result.multiple)
            .put("truncated",result.truncated).put("matches",matches)
    } catch (e: Exception) { screenError("invalid_template",e.message ?: "Invalid template or observation.") }

    private fun pixels(encoded: String, template: Boolean): Pixels = withMobilePng(encoded, template) { bitmap ->
        val w = bitmap.width
        val h = bitmap.height
        Pixels(IntArray(w*h).also { bitmap.getPixels(it,0,w,0,0,w,h) },w,h)
    }
}

/** Decode bounded PNG data. The bitmap is owned and recycled here. */
internal fun <T> withMobilePng(encoded: String, template: Boolean = false, read: (Bitmap) -> T): T {
    val byteLimit = if (template) 32768 else 6 * 1024 * 1024
    require(encoded.isNotEmpty() && encoded.length <= ((byteLimit + 2) / 3) * 4) { "PNG exceeds its encoded size limit." }
    val bytes = Base64.decode(encoded,Base64.NO_WRAP)
    require(bytes.size in 24..byteLimit && pngMagic.indices.all { bytes[it] == pngMagic[it] } &&
        bytes[12] == 'I'.code.toByte() && bytes[13] == 'H'.code.toByte() &&
        bytes[14] == 'D'.code.toByte() && bytes[15] == 'R'.code.toByte()) { "Expected PNG data." }
    val config = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,config)
    val w = config.outWidth
    val h = config.outHeight
    require(w > 0 && h > 0 && w.toLong()*h <= MobileIconMatcher.MAX_SCREEN_PIXELS &&
        (!template || w <= MobileIconMatcher.MAX_ICON_EDGE && h <= MobileIconMatcher.MAX_ICON_EDGE)) { "PNG dimensions exceed the limit." }
    config.inJustDecodeBounds = false
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,config)) { "Unreadable PNG." }
    return try {
        require(w == bitmap.width && h == bitmap.height) { "Unexpected decoded PNG dimensions." }
        read(bitmap)
    } finally { bitmap.recycle() }
}

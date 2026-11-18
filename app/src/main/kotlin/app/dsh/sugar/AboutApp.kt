package app.dsh.sugar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Metadata comes from the installed APK, so About follows every build automatically. */
internal object AboutApp {
    @Suppress("DEPRECATION")
    fun read(context: Context): JSONObject {
        val manager = context.packageManager
        val application = context.applicationInfo
        val version = manager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val icon = application.loadIcon(manager)
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val encoded = try {
            icon.setBounds(0, 0, bitmap.width, bitmap.height)
            icon.draw(Canvas(bitmap))
            ByteArrayOutputStream().use { bytes ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            bitmap.recycle()
        }
        return JSONObject().put("name", application.loadLabel(manager).toString())
            .put("version", version).put("icon", encoded)
    }
}

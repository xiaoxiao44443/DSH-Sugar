package app.dsh.sugar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.dsh.sugar.shared.WorkspaceEndpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Prepare the guest's initial JS and CSS bytes once, then serve them at its authenticated origin. */
internal class WebUiAssets(private val context: Context) {
    private val petPosters = object : LruCache<String, ByteArray>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val petFrames = LruCache<Int, ByteArray>(12)
    // Render the manifest icon with Android's adaptive mask, once on the WebView resource thread.
    private val railIcon by lazy {
        val icon = requireNotNull(context.getDrawable(context.applicationInfo.icon))
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, bitmap.width, bitmap.height)
        icon.draw(Canvas(bitmap))
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }

    private val bundle by lazy {
        try {
            context.assets.open("web-ui/startup.zip").use { WebUiStartupBundle.read(it) }
        } catch (_: IOException) {
            null
        }
    }

    /** Called on a background thread. Each served request gets an independent stream. */
    fun prepare(): Boolean = bundle != null

    fun intercept(request: WebResourceRequest, authenticatedOrigin: String?): WebResourceResponse? {
        if (authenticatedOrigin == null || request.isForMainFrame || request.method != "GET") return null
        val url = request.url.toString()
        if (!url.startsWith("$authenticatedOrigin/assets/")) return null
        val pets = "$authenticatedOrigin/assets/sugar/pets/"
        if (url.startsWith(pets)) return petAsset(url.removePrefix(pets))
        if (request.requestHeaders.keys.any { it.equals("Range", true) }) return null
        if (url == "$authenticatedOrigin/assets/sugar/launcher-icon.png") {
            return WebResourceResponse("image/png", null, 200, "OK",
                assetHeaders + ("Cache-Control" to "no-store"), railIcon.inputStream())
        }
        val found = bundle?.lookup(url, authenticatedOrigin) ?: return null
        return WebResourceResponse(found.mime, "utf-8", 200, "OK", assetHeaders, found.stream())
    }

    private fun petAsset(path: String): WebResourceResponse {
        if (path == "bubble.png") return petFrame(R.drawable.sugar_pet_bubble)
        val match = petPath.matchEntire(path) ?: return missingPet()
        val (pack, pose, extension) = match.destructured
        val pet = DesktopPets.packs(context).find { it.id == pack } ?: return missingPet()
        if (pose == "bubble" && extension == "png") return petFrame(pet.bubbleRes)
        if (pose !in DesktopPets.states) return missingPet()
        val asset = "pets/$pack/$pose.webp"
        return try {
            if (extension == "webp") petImage("image/webp", context.assets.open(asset))
            else petImage("image/png", poster(asset).inputStream())
        } catch (_: IOException) { missingPet() }
    }

    private fun petFrame(resId: Int): WebResourceResponse {
        // aapt strips the stretch markers. CSS slices the same artwork at matching xxhdpi coordinates.
        val png = petFrames.get(resId) ?: BitmapFactory.decodeResource(context.resources, resId,
            BitmapFactory.Options().apply { inScaled = false }).pngBytes().also { petFrames.put(resId, it) }
        return petImage("image/png", png.inputStream())
    }

    private fun poster(asset: String): ByteArray {
        petPosters.get(asset)?.let { return it }
        // decodeBitmap reads the initial frame of an animated WebP. Cache encoded
        // bytes only; WebView resource requests can arrive on separate IO threads.
        val source = ImageDecoder.createSource(context.assets, asset)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = info.size
            val longest = maxOf(size.width, size.height)
            if (longest > 256) {
                decoder.setTargetSize((size.width * 256 / longest).coerceAtLeast(1),
                    (size.height * 256 / longest).coerceAtLeast(1))
            }
        }
        return bitmap.pngBytes().also { petPosters.put(asset, it) }
    }

    private fun Bitmap.pngBytes(): ByteArray = try {
        val bytes = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bytes.toByteArray()
    } finally {
        recycle()
    }

    private fun petImage(type: String, bytes: InputStream) =
        WebResourceResponse(type, null, 200, "OK", petHeaders, bytes)

    private fun missingPet() =
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", petHeaders, null)

    companion object {
        private val assetHeaders = mapOf("Cache-Control" to "public, max-age=31536000, immutable",
            "Cross-Origin-Resource-Policy" to "same-origin", "X-Content-Type-Options" to "nosniff")
        // Pet URLs stay stable across app updates while their artwork can change.
        private val petHeaders = assetHeaders + ("Cache-Control" to "no-store")
        private val petPath = Regex("""([a-z0-9][a-z0-9_-]*)/([a-z]+)\.(png|webp)""")
    }
}

internal class WebUiStartupBundle private constructor(private val files: Map<String, Resource>) {
    class Resource(val mime: String, private val bytes: ByteArray) {
        fun stream(): InputStream = ByteArrayInputStream(bytes)
    }

    fun lookup(url: String, authenticatedOrigin: String): Resource? {
        val endpoint = WorkspaceEndpoint.parse(authenticatedOrigin) ?: return null
        if (!url.startsWith(endpoint.home)) return null
        return files[url.removePrefix(endpoint.home)]
    }

    companion object {
        private val entryName = Regex("""assets/[A-Za-z0-9_-]+-[A-Za-z0-9_-]{8,}\.(js|css)""")

        fun read(input: InputStream): WebUiStartupBundle = ZipInputStream(input).use { zip ->
            val files = linkedMapOf<String, Resource>()
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entryName.matches(name) || name in files) throw IOException("Invalid packaged DSH Web asset")
                val mime = if (name.endsWith(".css")) "text/css" else "text/javascript"
                files[name] = Resource(mime, zip.readBytes())
            }
            if (files.isEmpty()) throw IOException("Empty DSH Web asset bundle")
            WebUiStartupBundle(files)
        }
    }
}

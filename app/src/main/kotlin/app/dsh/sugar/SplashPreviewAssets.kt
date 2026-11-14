package app.dsh.sugar

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** The DSH document can embed the editor. Only its cross-origin child can discover the image URL. */
internal class SplashPreviewAssets(context: Context) {
    private val app = context.applicationContext
    private data class Session(val view: WebView, val parentOrigin: String, val document: String, val resources: String)
    @Volatile private var session: Session? = null

    fun attach(view: WebView, parentOrigin: String): String {
        val doc = "$ORIGIN/${UUID.randomUUID()}/index.html"
        session = Session(view, parentOrigin, doc, "$ORIGIN/${UUID.randomUUID()}/")
        return doc
    }

    fun detach() { session = null }

    fun intercept(view: WebView, request: WebResourceRequest, parentOrigin: String?): WebResourceResponse? {
        val url = request.url
        if (url.host != HOST) return null
        val current = session
        if (current == null || current.view !== view || current.parentOrigin != parentOrigin ||
            request.isForMainFrame || request.method != "GET") return missing()
        val raw = url.toString()
        return try {
            if (raw == current.document) {
                val policy = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; " +
                    "img-src 'self'; base-uri 'self'; form-action 'none'; frame-ancestors ${current.parentOrigin}"
                val markup = app.assets.open("sugar-splash/preview.html").bufferedReader().use { it.readText() }
                    .replaceFirst("<head>", "<head><base href=\"${current.resources}\">")
                respond("text/html", markup.byteInputStream(), mapOf("Content-Security-Policy" to policy))
            } else {
                if (!raw.startsWith(current.resources)) return missing()
                val path = raw.removePrefix(current.resources)
                when {
                    path == "layout.js" -> respond("application/javascript", app.assets.open("sugar-splash-layout.js"))
                    path == "default.png" -> defaultImage()
                    path == "image" || revision.matches(path) -> selectedImage()
                    else -> missing()
                }
            }
        } catch (_: IOException) { missing() }
    }

    private fun selectedImage(): WebResourceResponse {
        try {
            SplashSettings.customImage(app)?.let { file ->
                SplashSettings.imageMime(file)?.let { return respond(it, file.inputStream()) }
            }
        } catch (_: IOException) { /* A replaced image may already have been retired. */ }
        return defaultImage()
    }

    private fun defaultImage() = respond("image/png", app.assets.open("sugar-splash/default.png"))

    private fun respond(mime: String, input: InputStream, extra: Map<String, String> = emptyMap()): WebResourceResponse {
        val headers = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer", "Cross-Origin-Resource-Policy" to
                if (mime == "text/html") "cross-origin" else "same-origin")
        return WebResourceResponse(mime, if (mime.startsWith("image/")) null else "UTF-8", 200, "OK", headers + extra, input)
    }

    private fun missing() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), null)

    companion object {
        const val HOST = "splash-preview.invalid"
        private const val ORIGIN = "https://$HOST"
        private val revision = Regex("image\\?v=[0-9]{1,16}")
    }
}

package app.dsh.sugar

import android.content.Context
import android.webkit.WebResourceResponse
import app.dsh.sugar.shared.WorkspaceEndpoint
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** Offline legal text and link targets come from the installed APK, never from a page-supplied URL. */
internal object OpenSourceNotices {
    private val headers = mapOf("Cache-Control" to "no-store",
        "Cross-Origin-Resource-Policy" to "same-origin", "X-Content-Type-Options" to "nosniff")

    fun response(context: Context): WebResourceResponse = try {
        WebResourceResponse("application/json", "UTF-8", 200, "OK", headers,
            context.assets.open("open-source-notices.json"))
    } catch (_: IOException) {
        WebResourceResponse("application/json", "UTF-8", 503, "Service Unavailable", headers,
            """{"error":"notices_unavailable"}""".byteInputStream())
    }

    fun sourceUrl(context: Context, id: String): String? {
        // Read only the small APK index on click; the full license text stays off the UI thread.
        val links: Map<String, String> = try {
            val data = context.assets.open("open-source-links.json").bufferedReader().use { JSONObject(it.readText()) }
            if (data.optInt("schema") != 1) return null
            val components = data.getJSONArray("components")
            buildMap<String, String> {
                for (i in 0 until components.length()) {
                    val component = components.getJSONObject(i)
                    val key = component.getString("id")
                    if (containsKey(key)) return null
                    put(key, component.optString("sourceUrl"))
                }
            }
        } catch (_: IOException) { return null }
        catch (_: org.json.JSONException) { return null }
        return OpenSourcePolicy.componentSource(links, id)
    }

    @Suppress("DEPRECATION")
    fun releaseUrl(context: Context): String? = OpenSourcePolicy.releaseUrl(
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
}

internal object OpenSourcePolicy {
    const val PATH = "/__sugar__/open-source-notices.json"
    private val componentId = Regex("[a-z0-9][a-z0-9._-]{0,95}")
    private val version = Regex("""[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?""")

    fun matchesRequest(url: String, authenticatedOrigin: String?, method: String, mainFrame: Boolean): Boolean {
        if (mainFrame || method != "GET") return false
        val endpoint = authenticatedOrigin?.let(WorkspaceEndpoint::parse) ?: return false
        return url == endpoint.origin + PATH
    }

    fun componentSource(links: Map<String, String>, id: String): String? {
        if (!componentId.matches(id)) return null
        val target = links[id] ?: return null
        return try {
            val uri = URI(target)
            target.takeIf { uri.scheme == "https" && !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null && uri.port in setOf(-1, 443) }
        } catch (_: URISyntaxException) { null }
    }

    fun releaseUrl(installedVersion: String): String? = installedVersion.takeIf(version::matches)
        ?.let { "https://github.com/xiaoxiao44443/DSH-Sugar/releases/tag/v$it" }
}

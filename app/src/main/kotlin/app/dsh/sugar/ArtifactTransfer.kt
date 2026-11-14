package app.dsh.sugar

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

internal interface ArtifactOutput {
    val stream: OutputStream
    fun commit()
    fun abort()
}

internal fun artifactName(value: String): String {
    val name = value.substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl).trim()
    return if (name.isBlank() || name == "." || name == "..") "artifact" else name.take(255)
}

private val mimePattern = Regex("[a-z0-9][a-z0-9!#$&^_.+~-]*/[a-z0-9][a-z0-9!#$&^_.+~-]*")

internal fun artifactMediaType(name: String, type: String?): String {
    val supplied = type.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    if (supplied != "application/octet-stream" && mimePattern.matches(supplied)) return supplied
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

/** Streams one authorized, app-initiated export at a time to Android. */
internal class ArtifactTransfer(private val activity: Activity) {
    private val script by lazy { activity.assets.open("artifact-transfer.js").bufferedReader().use { it.readText() } }
    private var active: Link? = null

    fun attach(view: WebView, origin: String) {
        if (active?.let { it.view === view && it.origin == origin } == true) return
        detach()
        val ports = view.createWebMessageChannel()
        val link = Link(view, origin, ports[0])
        active = link
        val nonce = UUID.randomUUID().toString()
        link.port.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                if (active !== link || message.data == null) return
                val raw = message.data!!
                // One acknowledged 128 KiB binary chunk per message.
                if (raw.length > 180000) { detach(); return }
                io.execute { handle(link, raw) }
            }
        })
        view.evaluateJavascript(script.replace("__SUGAR_ARTIFACT_NONCE__", nonce)) {
            if (active === link) {
                view.postWebMessage(WebMessage(nonce, arrayOf(ports[1])), Uri.parse(origin))
            } else {
                ports[1].close()
            }
        }
    }

    fun detach() {
        val link = active ?: return
        active = null
        link.closed = true
        link.port.close()
        io.execute { link.abort() }
    }

    private class Link(val view: WebView, val origin: String, val port: WebMessagePort) {
        @Volatile var closed = false
        // These fields belong to the IO executor.
        var output: ArtifactOutput? = null
        var expected = 0L
        var received = 0L
        fun abort() {
            val current = output
            output = null
            runCatching { current?.abort() }
        }
    }

    private fun handle(link: Link, raw: String) {
        if (link.closed) return
        val packet = try { JSONObject(raw) } catch (_: Exception) { link.abort(); return }
        val seq = packet.optLong("seq", -1)
        val reply = JSONObject().put("seq", seq)
        try {
            when (packet.getString("op")) {
                "begin" -> {
                    check(link.output == null) { activity.sugarString(R.string.sugar_file_transfer_busy) }
                    val size = packet.getLong("size")
                    require(size in 0L..9007199254740991L) { activity.sugarString(R.string.sugar_file_invalid_size) }
                    val name = artifactName(packet.getString("name"))
                    val mime = artifactMediaType(name, packet.optString("mime"))
                    link.output = when (packet.getString("mode")) {
                        "open" -> ArtifactOpening.create(activity, name, mime)
                        "download" -> download(name, mime)
                        else -> throw IOException(activity.sugarString(R.string.sugar_file_invalid_action))
                    }
                    link.expected = size
                    link.received = 0
                }
                "chunk" -> {
                    val current = checkNotNull(link.output)
                    val bytes = Base64.decode(packet.getString("data"), Base64.NO_WRAP)
                    require(bytes.size in 1..131072 && packet.getLong("offset") == link.received &&
                        bytes.size <= link.expected - link.received) { activity.sugarString(R.string.sugar_file_incomplete) }
                    current.stream.write(bytes)
                    link.received += bytes.size
                }
                "finish" -> {
                    val current = checkNotNull(link.output)
                    check(!link.closed && link.received == link.expected) { activity.sugarString(R.string.sugar_file_interrupted) }
                    current.commit()
                    link.output = null
                }
                "abort" -> link.abort()
                else -> throw IOException(activity.sugarString(R.string.sugar_file_invalid_transfer))
            }
        } catch (error: Exception) {
            link.abort()
            reply.put("error", error.localizedMessage ?: activity.sugarString(R.string.sugar_file_operation_failed))
        }
        activity.runOnUiThread {
            if (active === link) link.port.postMessage(WebMessage(reply.toString()))
        }
    }

    private fun download(name: String, mime: String): ArtifactOutput {
        val resolver = activity.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException(activity.sugarString(R.string.sugar_file_download_create))
        val output = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException(activity.sugarString(R.string.sugar_file_download_write))
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
        return object : ArtifactOutput {
            override val stream = output
            override fun commit() {
                stream.close()
                val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                if (resolver.update(uri, published, null, null) != 1) throw IOException(activity.sugarString(R.string.sugar_file_download_finish))
            }
            override fun abort() {
                try { stream.close() } finally { resolver.delete(uri, null, null) }
            }
        }
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor { Thread(it, "sugar-artifact-io") }
    }
}

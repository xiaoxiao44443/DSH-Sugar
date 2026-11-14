package app.dsh.sugar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.Locale

internal object StoragePreviewText {
    const val LIMIT = 128 * 1024
    private val sourceExtensions = setOf(
        "md", "markdown", "txt", "log", "csv", "tsv", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "env", "properties", "json", "jsonl", "xml", "html", "htm", "css", "scss", "js", "mjs", "cjs",
        "ts", "jsx", "tsx", "py", "kt", "kts", "java", "go", "rs", "c", "h", "cpp", "hpp", "sh",
        "bash", "zsh", "sql", "gradle", "patch", "diff"
    )

    fun candidate(name: String, mime: String): Boolean =
        mime.startsWith("text/") || mime == "application/octet-stream" ||
        mime in setOf("application/json", "application/xml", "application/javascript", "application/yaml") ||
        mime.endsWith("+json") || mime.endsWith("+xml") ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in sourceExtensions

    /** Sources may have no trustworthy length. Read at most LIMIT + 1 bytes, even on a pipe. */
    fun read(input: InputStream, mediaType: String?): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= LIMIT) {
            val n = input.read(buffer, 0, minOf(buffer.size, LIMIT + 1 - output.size()))
            if (n < 0) break
            if (n == 0) return null
            output.write(buffer, 0, n)
        }
        if (output.size() > LIMIT) return null
        val bytes = output.toByteArray()
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size &&
            prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }

        val (offset, encoding) = when {
            starts(0xff, 0xfe, 0, 0) -> 4 to "UTF-32LE"
            starts(0, 0, 0xfe, 0xff) -> 4 to "UTF-32BE"
            starts(0xef, 0xbb, 0xbf) -> 3 to "UTF-8"
            starts(0xff, 0xfe) -> 2 to "UTF-16LE"
            starts(0xfe, 0xff) -> 2 to "UTF-16BE"
            else -> {
                val charset = mediaType.orEmpty().split(';').drop(1).map { it.trim() }
                    .firstOrNull { it.substringBefore('=').trim().equals("charset", ignoreCase = true) }
                    ?.substringAfter('=', "")?.trim()?.trim('"')
                0 to (charset ?: "UTF-8")
            }
        }
        val decoded = try {
            Charset.forName(encoding).newDecoder().decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: CharacterCodingException) { return null }
          catch (_: IllegalArgumentException) { return null }
        if (decoded.any { it.isISOControl() && it !in "\t\r\n" }) return null
        return decoded.replace("\r\n", "\n").replace('\r', '\n')
    }
}

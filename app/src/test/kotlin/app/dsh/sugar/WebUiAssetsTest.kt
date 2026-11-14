package app.dsh.sugar

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WebUiAssetsTest {
    private val origin = "http://127.0.0.1:32123"
    private val script = "assets/index-BgUyXZcc.js"
    private val style = "assets/vendor-DV2921Zo.css"

    private fun bundle(vararg names: String): WebUiStartupBundle {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("contents:$name".toByteArray())
                zip.closeEntry()
            }
        }
        return WebUiStartupBundle.read(output.toByteArray().inputStream())
    }

    @Test fun eachAssetResponseStartsAtTheFirstByte() {
        val resource = bundle(script).lookup("$origin/$script", origin)!!
        resource.stream().use { it.read() }
        assertEquals("contents:$script", resource.stream().bufferedReader().use { it.readText() })
    }

    @Test fun replayOnlyExactSubresourcesOfTheOwnedOrigin() {
        val bundle = bundle(script, style)
        val live = bundle.lookup("$origin/$script", origin)!!
        assertEquals("text/javascript", live.mime)
        assertEquals("text/css", bundle.lookup("$origin/$style", origin)!!.mime)
        listOf("$origin/", "$origin/api/plugins", "$origin/$script?token=x", "$origin/$script#frag",
            "$origin/assets/../$script", "$origin/assets/%69ndex-BgUyXZcc.js", "http://127.0.0.1:32124/$script",
            "$origin@evil.test/$script", "$origin.evil.test/$script", "$origin/assets/unknown-01234567.js")
            .forEach { assertNull(it, bundle.lookup(it, origin)) }
        assertNull(bundle.lookup("https://evil.test/$script", "https://evil.test"))
    }

    @Test fun rejectsUnexpectedBundledPaths() {
        for (name in listOf("assets/index.js","api/chats.js","assets/../index-BgUyXZcc.js","assets/shell-01234567.html")) {
            assertThrows(IOException::class.java) { bundle(name) }
        }
    }
}

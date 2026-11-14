package app.dsh.sugar

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SplashImageFormatTest {
    @get:Rule val folder = TemporaryFolder()
    // Two 2×2 frames in each format. The second tests both a local color table and GIF extensions.
    private val apng = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAACGFjVEwAAAACAAAAAPONk3AAAAAaZmNUTAAAAAAAAAACAAAAAgAAAAAAAAAAAAEACgAA6FTcAAAAABBJREFUeJxj/M8AAkxgkgEADR0BA4LJcf8AAAAaZmNUTAAAAAEAAAACAAAAAgAAAAAAAAAAAAEACgAAcyc21AAAABZmZEFUAAAAAnicY2Rg+M/AwMDEAAYACx8BAyVgX34AAAAASUVORK5CYII="
    private val gif = "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBCgABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7"

    private fun fixture(data: String): File = folder.newFile().apply { writeBytes(Base64.getDecoder().decode(data)) }

    @Test fun recognizesBothAnimationsWithoutAnExtension() {
        for ((data, mime) in listOf(apng to "image/png", gif to "image/gif")) {
            val file = fixture(data)
            assertEquals(mime, SplashImageFormat.mime(file))
            assertTrue(SplashImageFormat.complete(file, mime))
            file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 1))
            assertFalse(SplashImageFormat.complete(file, mime))
        }
        assertNull(SplashImageFormat.mime(folder.newFile().apply { writeText("not an image") }))
    }

    @Test fun rejectsDamagedLaterFramesEvenWithTheFirstFrameIntact() {
        val png = fixture(apng)
        val pngBytes = png.readBytes()
        val frame = pngBytes.toString(Charsets.ISO_8859_1).indexOf("fdAT")
        pngBytes[frame + 10] = (pngBytes[frame + 10].toInt() xor 1).toByte()
        png.writeBytes(pngBytes)
        assertFalse(SplashImageFormat.complete(png, "image/png"))

        val animation = fixture(gif)
        val bytes = animation.readBytes()
        val secondFrame = bytes.indexOfLast { it == 0x2c.toByte() }
        animation.writeBytes(bytes.copyOf(secondFrame + 3))
        assertFalse(SplashImageFormat.complete(animation, "image/gif"))
    }
}

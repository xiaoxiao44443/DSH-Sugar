package app.dsh.sugar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePreviewTextTest {
    @Test fun bomOverridesMimeAndMarkupRemainsText() {
        val source = "<script>中文</script>\r\n終"
        val utf16 = byteArrayOf(-1, -2) + source.toByteArray(Charsets.UTF_16LE)
        assertEquals(source.replace("\r\n", "\n"), StoragePreviewText.read(utf16.inputStream(), "text/plain; charset=US-ASCII"))
        val utf8 = byteArrayOf(-17, -69, -65) + "你好".toByteArray()
        assertEquals("你好", StoragePreviewText.read(utf8.inputStream(), null))
        assertEquals("", StoragePreviewText.read(byteArrayOf().inputStream(), null))
        assertTrue(StoragePreviewText.candidate("main.ts", "video/mp2t"))
    }

    @Test fun unsupportedEncodingMalformedTextAndBinaryFallBack() {
        val latin = "café".toByteArray(Charsets.ISO_8859_1)
        assertEquals("café", StoragePreviewText.read(latin.inputStream(), "text/plain; charset=\"ISO-8859-1\""))
        assertNull(StoragePreviewText.read(latin.inputStream(), null))
        assertNull(StoragePreviewText.read(byteArrayOf(0, 1, 2, 3).inputStream(), "text/plain"))
        assertNull(StoragePreviewText.read(byteArrayOf(1).inputStream(), "text/plain; charset=unknown-charset"))
    }

    @Test fun exactBoundIsReadableButOverLimitDoesNotDrainOriginal() {
        val buffer = ByteArray(StoragePreviewText.LIMIT + 100) { 65 }
        val stream = buffer.inputStream()
        assertNull(StoragePreviewText.read(stream, null))
        assertEquals(99, stream.available())
        assertEquals(StoragePreviewText.LIMIT, StoragePreviewText.read(buffer.inputStream(0, StoragePreviewText.LIMIT), null)?.length)
    }
}

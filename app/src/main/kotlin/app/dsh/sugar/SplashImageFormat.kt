package app.dsh.sugar

import java.io.EOFException
import java.io.File
import java.io.DataInputStream
import java.util.zip.CRC32

/** Platform image decoders can accept a complete first frame from a truncated animation. */
internal object SplashImageFormat {
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun mime(file: File): String? = file.inputStream().buffered().use { stream ->
        val header = ByteArray(8)
        if (stream.read(header) != header.size) return null
        when {
            header.contentEquals(pngSignature) -> "image/png"
            header.decodeToString(0, 6) in setOf("GIF87a", "GIF89a") -> "image/gif"
            else -> null
        }
    }

    fun complete(file: File, mime: String): Boolean = try {
        DataInputStream(file.inputStream().buffered(32768)).use { input ->
            when (mime) {
                "image/png" -> checkPng(input)
                "image/gif" -> checkGif(input)
                else -> false
            }
        }
    } catch (_: EOFException) { false }

    private fun checkPng(input: DataInputStream): Boolean {
        input.skipBytes(8)
        val type = ByteArray(4)
        val buffer = ByteArray(32 * 1024)
        val crc = CRC32()
        while (true) {
            val size = input.readInt()
            if (size < 0) return false
            input.readFully(type)
            crc.reset()
            crc.update(type)
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size)
                input.readFully(buffer, 0, count)
                crc.update(buffer, 0, count)
                remaining -= count
            }
            if (crc.value.toInt() != input.readInt()) return false
            if (type.decodeToString() == "IEND") return size == 0
        }
    }

    private fun checkGif(input: DataInputStream): Boolean {
        fun skipTable(flags: Int) {
            if (flags and 0x80 != 0) input.skipBytes(3 * (2 shl (flags and 7)))
        }
        fun skipBlocks() {
            while (true) {
                val size = input.readUnsignedByte()
                if (size == 0) return
                input.skipBytes(size)
            }
        }
        input.skipBytes(10) // packed fields of the logical screen descriptor
        val global = input.readUnsignedByte()
        input.skipBytes(2)
        skipTable(global)
        var hasFrame = false
        while (true) {
            when (input.readUnsignedByte()) {
                0x21 -> {
                    input.readUnsignedByte() // extension label
                    skipBlocks()
                }
                0x2c -> {
                    input.skipBytes(8) // image offset and dimensions
                    skipTable(input.readUnsignedByte())
                    input.readUnsignedByte() // minimum LZW code size
                    skipBlocks()
                    hasFrame = true
                }
                0x3b -> return hasFrame
                else -> return false
            }
        }
    }
}

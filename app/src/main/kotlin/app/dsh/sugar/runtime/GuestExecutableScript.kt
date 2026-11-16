package app.dsh.sugar.runtime

/** Linux treats a CR in a shebang as part of the interpreter path. Normalize before hashing
 * and installing helpers so a Windows checkout cannot produce an unusable executable. */
internal object GuestExecutableScript {
    fun normalize(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != '#'.code.toByte() || bytes[1] != '!'.code.toByte()) return bytes
        val output = ByteArray(bytes.size)
        var size = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\r'.code.toByte() && i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) continue
            output[size++] = bytes[i]
        }
        return if (size == bytes.size) bytes else output.copyOf(size)
    }
}

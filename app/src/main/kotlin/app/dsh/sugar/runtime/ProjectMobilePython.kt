package app.dsh.sugar.runtime

import android.content.Context
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Stream the signed ARM64 interpreter and its launchers into the shared tool installer. */
internal object ProjectMobilePython {
    private const val BASE = "/opt/sugar/mobile-python"
    private val python = """#!/bin/sh
export PYTHONPATH=/opt/sugar/mobile-use${'$'}{PYTHONPATH:+:${'$'}PYTHONPATH}
export LD_LIBRARY_PATH=$BASE/lib/aarch64-linux-gnu${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}
exec $BASE/bin/python3.11 "${'$'}@"
""".toByteArray()

    fun unpack(app: Context, version: String, add: (String, InputStream, Boolean) -> Unit) {
        val hash = MessageDigest.getInstance("SHA-256")
        app.assets.open("mobile-python/runtime.zip").use { raw ->
            val verified = DigestInputStream(raw.buffered(), hash)
            ZipInputStream(verified).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name.substringBefore('/') in setOf("bin", "lib", "share"))
                    add("$BASE/${entry.name}", zip, entry.name == "bin/python3.11")
                }
                // ZipInputStream stops at the central directory; include the unread suffix.
                val block = ByteArray(8192)
                while (verified.read(block) >= 0) { /* finish digest */ }
            }
        }
        check(hash.digest().joinToString("") { "%02x".format(it) } == version) { "CPython 资源校验失败。" }
        add("$BASE/bin/python3", python.inputStream(), true)
        for (name in listOf("python", "python3", "pip", "pip3")) {
            val target = if (name.startsWith("pip")) "$BASE/bin/python3.11 -P -m pip" else "$BASE/bin/python3"
            add("/usr/bin/$name", "#!/bin/sh\nexec $target \"\$@\"\n".byteInputStream(), true)
        }
    }
}

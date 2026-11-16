package app.dsh.sugar.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GuestExecutableScriptTest {
    @Test fun nodeShebangAndBodyUseLinuxLineEndings() {
        val source = "#!/usr/bin/node\r\nconsole.log('hello');\r\n".toByteArray()
        assertArrayEquals("#!/usr/bin/node\nconsole.log('hello');\n".toByteArray(), GuestExecutableScript.normalize(source))
    }

    @Test fun shellLauncherUsesLinuxLineEndings() {
        val source = "#!/bin/sh\r\nexec python3 mobile_use.py \"\$@\"\r\n".toByteArray()
        assertArrayEquals("#!/bin/sh\nexec python3 mobile_use.py \"\$@\"\n".toByteArray(), GuestExecutableScript.normalize(source))
    }

    @Test fun linuxScriptIsUnchanged() {
        val source = "#!/usr/bin/node\nconsole.log('hello');\n".toByteArray()
        assertSame(source, GuestExecutableScript.normalize(source))
    }

    @Test fun loneCarriageReturnAndUtf8BytesArePreserved() {
        val source = "#!/bin/sh\r\nprintf '你好\rworld'\r\n".toByteArray()
        assertArrayEquals("#!/bin/sh\nprintf '你好\rworld'\n".toByteArray(), GuestExecutableScript.normalize(source))
    }

    @Test fun binaryAndNonExecutableAssetsAreUnchanged() {
        for (source in listOf(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 13, 10, -1), "text\r\n".toByteArray(), byteArrayOf(), byteArrayOf(35))) {
            assertSame(source, GuestExecutableScript.normalize(source))
        }
    }
}

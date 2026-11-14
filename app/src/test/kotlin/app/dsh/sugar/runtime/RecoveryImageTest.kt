package app.dsh.sugar.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class RecoveryImageTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun environment(id: String): Path =
        Files.createDirectories(temporary.root.toPath().resolve("environments/$id/workspace"))

    private fun put(path: Path, value: String) {
        Files.createDirectories(path.parent)
        Files.write(path, value.toByteArray())
    }

    @Test fun reusesOnlyStockContentAcrossBothKindsOfSession() {
        val first = environment("first")
        val cold = RecoveryImage(first)
        assertFalse(cold.checkout("v1"))
        put(first.resolve("opt/dsh/program"), "APK")
        put(first.resolve("usr/bin/node"), "node")
        put(first.parent.resolve("sugar-image.sha256"), "v1")
        put(first.parent.resolve("sugar-network-tools.json"), "network")
        put(first.resolve("tmp/recovery-home/token"), "private")
        put(first.resolve("workspace/conversation"), "private")
        cold.park()

        val second = environment("second")
        val modified = RecoveryImage(second)
        assertTrue(modified.checkout("v1"))
        assertEquals("node", second.resolve("usr/bin/node").toFile().readText())
        assertEquals("network", second.parent.resolve("sugar-network-tools.json").toFile().readText())
        assertFalse(Files.exists(second.resolve("workspace")))
        assertFalse(Files.exists(second.resolve("tmp")))
        modified.detachStockProgram()
        put(second.resolve("opt/dsh/program"), "current user installation")
        modified.park()
        val third = environment("third")
        assertTrue(RecoveryImage(third).checkout("v1"))
        assertEquals("APK", third.resolve("opt/dsh/program").toFile().readText())
    }

    @Test fun changedSignedImageAndInterruptedCheckoutBothFallBackToCleanProvisioning() {
        val first = environment("first")
        put(first.resolve("opt/dsh/old-package"), "old")
        put(first.parent.resolve("sugar-image.sha256"), "old")
        RecoveryImage(first).park()
        val second = environment("second")
        assertFalse(RecoveryImage(second).checkout("new"))
        assertFalse(Files.exists(second.resolve("opt")))
        put(second.parent.resolve("sugar-image.sha256"), "new")
        put(second.resolve("opt/dsh/new-package"), "new")
        RecoveryImage(second).park()
        val interrupted = environment("interrupted")
        assertTrue(RecoveryImage(interrupted).checkout("new"))
        // The dead manager's unpublished environment cannot supply files to the next session.
        val next = environment("next")
        assertFalse(RecoveryImage(next).checkout("new"))
        assertFalse(Files.exists(next.resolve("opt")))
    }
}

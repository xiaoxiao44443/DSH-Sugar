package app.dsh.sugar.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetainedWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retainsTheTreeAndLinkBodiesAcrossRemovalAndFreshEnvironment() {
        val guest = temporary.newFolder("original","workspace").toPath()
        val files = Files.createDirectories(guest.resolve("workspace/nested"))
        Files.write(files.resolve("note.txt"), "hello".toByteArray())
        Files.write(guest.resolve("scratch.txt"), byteArrayOf(1))
        val store = Files.createDirectory(guest.resolveSibling("workspace.sucrune-inodes-v1"))
        Files.write(store.resolve("body"), byteArrayOf(42))
        val retained = RetainedWorkspace(temporary.root.toPath().resolve("archive"))
        repeat(2) { retained.retain(guest) }
        assertEquals("hello",retained.files.resolve("nested/note.txt").toFile().readText())
        assertFalse(Files.exists(retained.directory.resolve("scratch.txt")))
        assertTrue(Files.exists(guest.resolve("scratch.txt")))
        guest.parent.toFile().deleteRecursively()
        val fresh = temporary.newFolder("replacement","workspace").toPath()
        Files.createDirectory(fresh.resolve("workspace"))
        Files.createDirectory(fresh.resolveSibling("workspace.sucrune-inodes-v1"))
        repeat(2) { retained.restore(fresh) }
        assertEquals("hello",fresh.resolve("workspace/nested/note.txt").toFile().readText())
        assertEquals(42,Files.readAllBytes(fresh.resolveSibling("workspace.sucrune-inodes-v1/body")).single().toInt())
    }
}

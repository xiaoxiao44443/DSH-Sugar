package app.dsh.sugar.runtime

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetainedRootDeletionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val path = "/home/bbb"
    private val owner = mapOf(path to "old")

    @Test fun onlyUnregisteredIndependentDirectoriesWithMatchingIdentityCanBeDeleted() {
        assertTrue(canRemoveRetainedRoot("old",path, emptySet(),emptySet(),owner))
        assertFalse(canRemoveRetainedRoot("old",path,setOf("old"),emptySet(),owner))
        assertFalse(canRemoveRetainedRoot("old",path,emptySet(),setOf("old"),owner))
        assertFalse(canRemoveRetainedRoot("old",path,emptySet(),emptySet(),mapOf(path to "new")))
        assertFalse(canRemoveRetainedRoot("old",path,emptySet(),emptySet(),owner + ("$path/sub" to null)))
        assertFalse(canRemoveRetainedRoot("old","$path/sub",emptySet(),emptySet(),owner + ("$path/sub" to "old")))
        for (protected in listOf("/etc", "/home/sugar", "/home/sugar/.dsh",
                "/workspace/deepseek-harness/default-workspace", "/workspace/local-" + "a".repeat(64))) {
            assertFalse(canRemoveRetainedRoot("old",protected,emptySet(),emptySet(),mapOf(protected to "old")))
        }
    }

    @Test fun repeatAfterStagingNeverCapturesSameNameReplacement() {
        val source = temporary.newFolder("original").toPath()
        Files.write(source.resolve("user.txt"), byteArrayOf(42))
        val staged = temporary.root.toPath().resolve("held")
        fun identity(path: Path) = if (Files.exists(path.resolve("user.txt"))) "old" else "new"
        assertTrue(RetainedRootDeletion.stageOriginal(source,staged,"old",::identity))
        Files.createDirectory(source)
        Files.write(source.resolve("unrelated.txt"), byteArrayOf(13))
        assertTrue(RetainedRootDeletion.stageOriginal(source,staged,"old",::identity))
        assertEquals(42, Files.readAllBytes(staged.resolve("user.txt")).single().toInt())
        assertEquals(13, Files.readAllBytes(source.resolve("unrelated.txt")).single().toInt())
    }

    @Test fun replacementDuringCaptureRestoresItsContents() {
        val source = temporary.newFolder("replaced").toPath()
        Files.write(source.resolve("other.txt"), byteArrayOf(7))
        val staged = temporary.root.toPath().resolve("held")
        assertFalse(RetainedRootDeletion.stageOriginal(source,staged,"old") { if (it == source) "old" else "new" })
        assertFalse(Files.exists(staged))
        assertEquals(7,Files.readAllBytes(source.resolve("other.txt")).single().toInt())
    }

    @Test fun callbacksDescribeUserFilesFromEitherTreeAndExcludeInodeStore() {
        assertEquals("",RetainedRootDeletion.relativeDocument("archive/workspace"))
        assertEquals("media/example",RetainedRootDeletion.relativeDocument("archive/original-directory/media/example"))
        assertEquals("a",RetainedRootDeletion.relativeDocument("archive/workspace/a"))
        assertNull(RetainedRootDeletion.relativeDocument("archive/inodes/body"))
        assertNull(RetainedRootDeletion.relativeDocument("archive/workspace-copy/a"))
    }
}

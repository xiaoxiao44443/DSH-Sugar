package app.dsh.sugar.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FolderRevealPathTest {
    @Test fun directoryOpensItselfAndFileOpensParent() {
        assertEquals(listOf("localdream-pack", "dsh-localdream"),
            folderRevealComponents("localdream-pack/dsh-localdream", true))
        assertEquals(listOf("localdream-pack"),
            folderRevealComponents("localdream-pack/README.md", false))
        assertEquals(emptyList<String>(), folderRevealComponents("README.md", false))
    }

    @Test fun workspaceRootStaysRoot() {
        assertEquals(emptyList<String>(), folderRevealComponents("", true))
        assertEquals(emptyList<String>(), folderRevealComponents("", false))
    }

    @Test fun invalidPathsCannotEscapeOrHideInTheLeaf() {
        for (path in listOf("../private", "/workspace", "a/../private", "a/", "a//b")) {
            for (directory in listOf(true, false)) {
                assertThrows(IllegalArgumentException::class.java) { folderRevealComponents(path, directory) }
            }
        }
    }
}

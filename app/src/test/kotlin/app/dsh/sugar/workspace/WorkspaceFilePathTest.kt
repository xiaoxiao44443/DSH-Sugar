package app.dsh.sugar.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceFilePathTest {
    @Test fun listsRootAndAcceptsOrdinaryNames() {
        assertEquals(emptyList<String>(), workspaceFilePath(""))
        assertEquals(listOf(".notes", "一张 图.png"), workspaceFilePath(".notes/一张 图.png"))
    }

    @Test fun rejectsReferencesOutsideTheCurrentDirectoryAndOverlongUtf8() {
        for (path in listOf("/", "/a", "a/../b", ".", "../x", "a//b", "a/", "a\\b", "a\u0000b",
            "界".repeat(86), List(18) { "x".repeat(255) }.joinToString("/"))) {
            assertThrows(IllegalArgumentException::class.java) { workspaceFilePath(path) }
        }
    }
}

package app.dsh.sugar.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRootTest {
    @Test fun acceptsBothHomeLayoutsAndProjectMounts() {
        val roots = listOf("/home/xxx", "/home/我的项目/手册", "/home/sugar/old-layout",
            "/workspace/project", "/workspace/deepseek-harness/default-workspace",
            "/workspace/local-" + "2".repeat(64))
        roots.forEach { assertTrue(it, isWorkspaceRoot(it)) }
    }

    @Test fun rejectsSystemAndPrivateFoldersAndInvalidPaths() {
        val invalid = listOf("/", "/usr/local/test", "/home", "/home/sugar", "/workspace",
            "/workspace/deepseek-harness", "/home/../etc", "/home//project", "/home/project/",
            "/home/sugar/.dsh", "/home/project/.ssh", "/workspace/hidden/.config",
            "/workspace/local-" + "2".repeat(64) + "/nested", "/home/bad\\dir",
            "/home/new\nline", "/workspace/   ", "/home/" + "界".repeat(1400))
        invalid.forEach { assertFalse(it, isWorkspaceRoot(it)) }
    }
}

package app.dsh.sugar.workspace

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFolderMergeTest {
    private class TestTree(vararg entries: Pair<String, String>) : FolderTree {
        val files = mutableMapOf(*entries)
        override fun scan() = files.mapValues { if (it.value == DIRECTORY) DIRECTORY else it.value.byteInputStream().use { s -> transferDigest(s) } }
        override fun read(path: String) = files.getValue(path).byteInputStream()
        override fun write(path: String, source: InputStream, digest: String, expected: String?) {
            assertEquals(expected,scan()[path])
            val new = source.readBytes()
            assertEquals(digest,new.inputStream().use { transferDigest(it) })
            files[path] = new.toString(Charsets.UTF_8)
        }
        override fun mkdir(path: String) { check(path !in files); files[path] = DIRECTORY }
        override fun remove(path: String, expected: String) {
            assertEquals(expected,scan()[path])
            assertFalse(files.keys.any { it.startsWith("$path/") })
            files.remove(path)
        }
    }

    @Test fun importsDirectoriesAndPropagatesIndependentChangesAndDeletions() {
        val phone = TestTree("src" to DIRECTORY, "src/file" to "original")
        val project = TestTree("local" to "project file")
        val common = mutableMapOf<String,String>()
        assertEquals(3,mergeWorkspaceFolders(project,phone,common).transferred)
        assertEquals(project.files,phone.files)
        phone.files["src/file"] = "changed on phone"
        project.files.remove("local")
        assertEquals(2,mergeWorkspaceFolders(project,phone,common).transferred)
        assertEquals(project.files,phone.files)
        phone.files.clear()
        assertEquals(2,mergeWorkspaceFolders(project,phone,common).transferred)
        assertTrue(project.files.isEmpty())
        assertTrue(common.isEmpty())
    }

    @Test fun retainsBothConcurrentEditsUntilOwnerChoosesAndRejectsDeletedSubtreeWithChanges() {
        val phone = TestTree("d" to DIRECTORY,"d/file" to "initial","d/sibling" to "retain")
        val project = TestTree()
        val common = mutableMapOf<String,String>()
        mergeWorkspaceFolders(project,phone,common)
        phone.files["d/file"] = "phone"
        project.files["d/file"] = "linux"
        assertEquals(listOf("d/file"),mergeWorkspaceFolders(project,phone,common).conflicts)
        assertEquals("phone",phone.files["d/file"])
        assertEquals("linux",project.files["d/file"])
        assertEquals(1,mergeWorkspaceFolders(project,phone,common,"d/file" to true).transferred)
        project.files.clear()
        phone.files["d/new"] = "new"
        assertEquals(listOf("d"),mergeWorkspaceFolders(project,phone,common).conflicts)
        assertEquals("retain",phone.files["d/sibling"])
        assertTrue(project.files.isEmpty())
        assertTrue(mergeWorkspaceFolders(project,phone,common,"d" to true).conflicts.isEmpty())
        assertEquals(phone.files,project.files)
    }
}

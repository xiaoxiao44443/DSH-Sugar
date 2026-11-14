package app.dsh.sugar.workspace

import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateWorkspaceTreePathTest {
    @get:Rule val temporary = TemporaryFolder()
    private val env = "env_0123456789abcdef0123456789abcdef"
    private val components = listOf("sucrune", "environments", env, "workspace", "workspace")
    private fun project(base: File) = components.fold(base) { parent, name -> File(parent, name) }

    @Test fun acceptsBothSpellingsOfTheSamePrivateDirectory() {
        val files = temporary.newFolder("files")
        val alias = temporary.root.resolve("runtime-files").toPath()
        try {
            Files.createSymbolicLink(alias, files.toPath())
        } catch (e: FileSystemException) {
            // Windows allows directory junctions even when symbolic link creation needs elevation.
            if (File.separatorChar != '\\') throw e
            val junction = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", alias.toString(), files.path)
                .redirectErrorStream(true).start()
            val output = junction.inputStream.bufferedReader().use { it.readText() }
            check(junction.waitFor() == 0) { output }
        }
        try {
            assertEquals(components, projectTreeComponents(files, project(files)))
            assertEquals(components, projectTreeComponents(files, project(alias.toFile())))
        } finally {
            Files.delete(alias)
        }
    }

    @Test fun rejectsAnotherStorageRootAndNonProjectPaths() {
        val files = temporary.newFolder("files")
        val otherFiles = temporary.newFolder("other", "files")
        for (invalid in listOf(project(otherFiles), files, project(files).parentFile,
            project(files).resolve("child"), files.resolve("sucrune/environments/unregistered/workspace/workspace"))) {
            assertThrows(IllegalArgumentException::class.java) { projectTreeComponents(files, invalid) }
        }
    }

    @Test fun acceptsOnlyManagerOwnedRetainedDirectories() {
        val files = temporary.newFolder("files")
        val id = "01234567-89ab-4abc-bdef-0123456789ab"
        val retained = listOf("sucrune","sugar-retained",id,"workspace")
        val root = retained.fold(files,File::resolve)
        assertEquals(retained, projectTreeComponents(files,root))
        assertThrows(IllegalArgumentException::class.java) { projectTreeComponents(files,files.resolve("sucrune/sugar-retained/fake/workspace")) }
    }
}

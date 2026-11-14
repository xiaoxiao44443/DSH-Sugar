package app.dsh.sugar.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SharedDshHomeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sharedInstallSourceIsRestrictedToPublishedPackageRoots() {
        val snapshot = "/home/sugar/.dsh/sugar-plugin-sources/" + "a".repeat(64)
        val created = "/home/sugar/.dsh/profiles/web/created-plugins/40543feb-6425-49ea-99ea-425f5e212a77"
        for (path in listOf(snapshot, "$snapshot-2", created))
            assertTrue(path, ProjectBundleExport.isSharedBundleSource(path))
        for (path in listOf("$snapshot/src","$created/../../..","$created/", "/home/sugar/.dsh/sessions",
            "/home/sugar/.dsh/profiles/web", "/home/sugar/.sugar-project-state", "/workspace/plugin"))
            assertFalse(path, ProjectBundleExport.isSharedBundleSource(path))
    }

    @Test fun interruptedRoutingMigrationResumesAndOnlyImportsOnce() {
        val original = temp.newFolder("dsh").toPath()
        val state = temp.newFolder("state").toPath()
        val receipt = temp.root.toPath().resolve("routing.done")
        val pins = Files.createDirectory(state.resolve("sugar-session-projects"))
        Files.write(pins.resolve("known"), byteArrayOf(7))
        val removals = Files.createDirectory(original.resolve("sugar-project-removals"))
        Files.write(removals.resolve("pending.json"), byteArrayOf(8))
        SharedDshHome.relocateRouting(original, state, receipt)
        assertEquals(8, Files.readAllBytes(state.resolve("sugar-project-removals/pending.json")).single().toInt())
        assertFalse(Files.exists(removals))
        Files.createDirectory(original.resolve("sugar-session-projects"))
        Files.write(original.resolve("sugar-session-projects/untrusted"), byteArrayOf(9))
        SharedDshHome.relocateRouting(original, state, receipt)
        assertTrue(Files.exists(original.resolve("sugar-session-projects/untrusted")))
        assertFalse(Files.exists(pins.resolve("untrusted")))
        assertTrue(Files.exists(pins.resolve("known")))
    }

    @Test fun privateHomeSurvivesMountPreparationAlongsideExistingBackup() {
        val home = temp.newFolder("project-home").toPath()
        val receipt = temp.root.toPath().resolve("project.done")
        Files.createDirectory(home.resolve(".dsh-before-sharing"))
        Files.createDirectory(home.resolve(".dsh"))
        Files.write(home.resolve(".dsh/custom"), byteArrayOf(42))
        repeat(2) { SharedDshHome.preserveLocalHome(home, receipt) }
        assertTrue(Files.isDirectory(home.resolve(".dsh")))
        assertFalse(Files.exists(home.resolve(".dsh/custom")))
        assertEquals(42, Files.readAllBytes(home.resolve(".dsh-before-sharing-2/custom")).single().toInt())
        assertFalse(Files.exists(home.resolve(".dsh-before-sharing-3")))
    }
}

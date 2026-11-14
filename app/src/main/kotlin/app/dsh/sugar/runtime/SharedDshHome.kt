package app.dsh.sugar.runtime

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes

internal object SharedDshHome {
    const val PATH = "/home/sugar/.dsh"
    const val ROUTING = "/home/sugar/.sugar-project-state"
    private const val HOME = "/home/sugar"
    private const val CONTROL_RECEIPT = "sugar-routing-migrated-v1"
    private const val PROJECT_RECEIPT = "sugar-shared-dsh-v1"
    private val routingDirectories = listOf("sugar-session-projects", "sugar-project-removals")

    /** Runs before the Host or any project can use the shared directory, including after an interrupted upgrade. */
    @Synchronized fun prepareControl(root: Path) {
        val receipt = root.parent.resolve(CONTROL_RECEIPT)
        if (Files.isRegularFile(receipt, NOFOLLOW_LINKS)) return
        ProjectTreeCopy.openSource(root, HOME).use { home ->
            ProjectTreeCopy.openSource(root, PATH).use { original ->
                val state = Paths.get("/proc/self/fd/${home.fd}", ROUTING.substringAfterLast('/'))
                if (attributes(state) == null) Files.createDirectory(state)
                ProjectTreeCopy.directory(state.toString()).use { destination ->
                    relocateRouting(Paths.get("/proc/self/fd/${original.fd}"),
                        Paths.get("/proc/self/fd/${destination.fd}"), receipt)
                }
            }
        }
    }

    internal fun relocateRouting(original: Path, destination: Path, receipt: Path) {
        if (Files.isRegularFile(receipt, NOFOLLOW_LINKS)) return
        for (name in routingDirectories) {
            val source = original.resolve(name)
            val sourceInfo = attributes(source)
            val target = destination.resolve(name)
            val targetInfo = attributes(target)
            check(targetInfo == null || targetInfo.isDirectory) { "无效的项目管理目录：$name" }
            if (sourceInfo == null) continue
            check(sourceInfo.isDirectory && targetInfo == null) { "项目管理记录迁移冲突：$name" }
            Files.move(source, target, ATOMIC_MOVE)
        }
        // Outside the guest tree. A directory later created under the shared home is never imported as authority.
        Files.createFile(receipt)
    }

    @Synchronized fun prepareProject(root: Path) {
        val receipt = root.parent.resolve(PROJECT_RECEIPT)
        if (Files.isRegularFile(receipt, NOFOLLOW_LINKS)) return
        ProjectTreeCopy.openSource(root, HOME).use { home ->
            preserveLocalHome(Paths.get("/proc/self/fd/${home.fd}"), receipt)
        }
    }

    internal fun preserveLocalHome(home: Path, receipt: Path) {
        if (Files.isRegularFile(receipt, NOFOLLOW_LINKS)) return
        val mountpoint = home.resolve(".dsh")
        val entry = attributes(mountpoint)
        if (entry != null && (!entry.isDirectory || Files.newDirectoryStream(mountpoint).use { it.iterator().hasNext() })) {
            var backup = home.resolve(".dsh-before-sharing")
            var index = 2
            while (attributes(backup) != null) backup = home.resolve(".dsh-before-sharing-${index++}")
            Files.move(mountpoint, backup, ATOMIC_MOVE)
        }
        if (attributes(mountpoint) == null) Files.createDirectory(mountpoint)
        Files.createFile(receipt)
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }
}

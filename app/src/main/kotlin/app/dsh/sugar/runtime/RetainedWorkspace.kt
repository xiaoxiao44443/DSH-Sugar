package app.dsh.sugar.runtime

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Moves project data on the same filesystem, in restartable steps, without walking user files. */
internal class RetainedWorkspace(val directory: Path) {
    val files: Path get() = directory.resolve("workspace")
    private val inodes get() = directory.resolve("inodes")
    private fun inodeStore(guest: Path) = guest.resolveSibling(guest.fileName.toString() + ".sucrune-inodes-v1")

    fun retain(guest: Path) {
        Files.createDirectories(directory)
        val store = inodeStore(guest)
        if (Files.exists(store, NOFOLLOW_LINKS)) moveDirectory(store, inodes)
        if (!Files.exists(files, NOFOLLOW_LINKS)) moveDirectory(guest.resolve("workspace"), files)
        check(Files.isDirectory(files, NOFOLLOW_LINKS))
    }

    fun restore(guest: Path) {
        if (Files.exists(inodes, NOFOLLOW_LINKS)) {
            // An unused fresh store can exist after environment initialization.
            Files.deleteIfExists(inodeStore(guest))
            moveDirectory(inodes, inodeStore(guest))
        }
        val dest = guest.resolve("workspace")
        if (Files.exists(files, NOFOLLOW_LINKS)) {
            // ProjectUserland may already have created an empty mount point. Never replace user data.
            Files.deleteIfExists(dest)
            moveDirectory(files, dest)
        }
        check(Files.isDirectory(dest, NOFOLLOW_LINKS))
        Files.deleteIfExists(directory)
    }

    private fun moveDirectory(from: Path, to: Path) {
        check(Files.isDirectory(from, NOFOLLOW_LINKS) && !Files.exists(to, NOFOLLOW_LINKS))
        Files.move(from, to, ATOMIC_MOVE)
    }
}

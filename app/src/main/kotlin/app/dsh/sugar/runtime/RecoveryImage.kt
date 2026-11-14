package app.dsh.sugar.runtime

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Lend the APK's read-only system trees to one workspace-write recovery session at a time.
 * Sucrune only admits writes under /tmp and /workspace. Neither path nor the original
 * running program borrowed for diagnosis may enter the cache. All moves are on app storage. */
internal class RecoveryImage(private val root: Path) {
    private val metadata = root.parent
    private val cache = metadata.parent.parent.resolve("recovery-image-v1")
    private val staging = metadata.resolve("recovery-image-transfer")
    private val stockProgram = metadata.resolve("recovery-stock-dsh")
    private val receipts = listOf("sugar-image.sha256", "sugar-network-tools.json")
    private var detached = false

    /** Claim the whole cache before moving anything out. A killed transfer is discarded
     * with its ephemeral environment; the next attempt can always rebuild from the APK. */
    fun checkout(expectedDigest: String): Boolean {
        if (Files.notExists(cache, NOFOLLOW_LINKS)) return false
        Files.move(cache, staging, ATOMIC_MOVE)
        val receipt = staging.resolve(receipts.first())
        if (!Files.isRegularFile(receipt, NOFOLLOW_LINKS) || receipt.toFile().readText() != expectedDigest) {
            GuestImage.removeTree(staging)
            return false
        }
        transferRoots(staging, root)
        for (name in receipts) moveIfPresent(staging.resolve(name), metadata.resolve(name))
        Files.delete(staging)
        return true
    }

    fun detachStockProgram() {
        Files.move(root.resolve("opt/dsh"), stockProgram, ATOMIC_MOVE)
        detached = true
    }

    /** The manager stops the recovery worker and all of its children before publication. */
    fun park() {
        if (detached) {
            moveIfPresent(root.resolve("opt/dsh"), metadata.resolve("discard-running-dsh"))
            Files.move(stockProgram, root.resolve("opt/dsh"), ATOMIC_MOVE)
        }
        Files.createDirectory(staging)
        transferRoots(root, staging)
        for (name in receipts) moveIfPresent(metadata.resolve(name), staging.resolve(name))
        Files.move(staging, cache, ATOMIC_MOVE)
    }

    private fun transferRoots(source: Path, destination: Path) {
        for (name in GuestImage.systemRoots) moveIfPresent(source.resolve(name), destination.resolve(name))
    }

    private fun moveIfPresent(source: Path, destination: Path) {
        if (Files.exists(source, NOFOLLOW_LINKS)) Files.move(source, destination, ATOMIC_MOVE)
    }
}

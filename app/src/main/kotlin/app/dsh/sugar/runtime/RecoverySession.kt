package app.dsh.sugar.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.sucrune.probe.NativeProbe
import android.system.ErrnoException
import android.system.OsConstants
import app.dsh.sugar.R
import app.dsh.sugar.sugarString
import com.sucrune.probe.GuestDirectoryBind
import com.sucrune.probe.RuntimeClient
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

internal object RecoverySession {
    private const val recoveryName = "DSH Sugar Recovery"
    private const val profileDir = "/home/sugar/.dsh/profiles/web"
    private var activeImage: RecoveryImage? = null
    private var activeExecution: String? = null
    @Volatile var profileProject: String? = null
        private set

    private fun find(client: RuntimeClient, name: String): String? {
        val rows = client.listEnvironments()
        return (0 until rows.length()).map(rows::getJSONObject).filter { it.getString("name") == name }
            .also { check(it.size <= 1) { "Ambiguous runtime identity" } }.singleOrNull()?.getString("id")
    }

    // Called before a normal start, and under the maintenance gate before starting a rescue.
    // Discover by reserved name so process death between env.create and setup is recoverable.
    internal fun cleanOrphans(client: RuntimeClient) {
        find(client, recoveryName)?.let {
            GuestTimeZone.forget(it)
            client.deleteProject(it, purgeExecutionHistory = true)
        }
    }

    private fun controlId(client: RuntimeClient): String =
        find(client, "DSH Sugar") ?: client.createEnvironment("DSH Sugar").getString("id")

    private fun control(context: Context, client: RuntimeClient): Path = GuestImage.root(context, controlId(client))

    @Synchronized fun start(context: Context, builtin: Boolean, progress: (String) -> Unit): String {
        val client = RuntimeClient.get(context.applicationContext)
        try {
            SugarRuntime.beginMaintenance(context)
            cleanOrphans(client)
            val originalId = controlId(client)
            val original = GuestImage.root(context, originalId)
            finishProgramRestore(original)
            val project = client.createEnvironment(recoveryName)
            val id = project.getString("id")
            val root = GuestImage.root(context, id)
            val image = RecoveryImage(root)
            val bundle = GuestImage.bundle(context)
            val started = SystemClock.elapsedRealtime()
            val cached = image.checkout(bundle.getString("sha256"))
            if (cached) progress(context.sugarString(R.string.recovery_image_reuse))
            GuestImage.prepare(context, client, project, progress, bundle)
            activeImage = image
            Log.i("SugarRecovery", "Recovery image ${if (cached) "reused" else "built"} in ${SystemClock.elapsedRealtime() - started} ms")
            if (!builtin) {
                // Run a detached copy. Edits to the broken installation cannot change executing code.
                progress(context.sugarString(R.string.recovery_program_copy))
                val program = root.resolve("opt/dsh")
                image.detachStockProgram()
                Files.createDirectory(program)
                ProjectTreeCopy.copy(original, "/opt/dsh", program)
            }
            for (dir in listOf("/workspace", "/tmp", "/tmp/recovery-home/.dsh")) client.makeDirectory(id, dir)
            val mount = RecoveryDirectory(root.resolve("workspace"), original)
            client.makeDirectory(originalId, "/home/sugar/.dsh")
            val patch = "/opt/sugar/recovery-workspace.patch.yml"
            val plugin = "/opt/sugar/dsh-sugar-navigation"
            client.makeDirectory(id, plugin)
            for (name in listOf("package.json", "index.js", "client.js")) {
                context.assets.open("guest/dsh-sugar-navigation/$name").use { client.writeFile(id, "$plugin/$name", it.readBytes()) }
            }
            val diagnosticPolicy = RecoveryPolicy.install(context, client, id, builtin)
            client.writeFile(id, patch, ("- id: workspace-controller\n  config:\n    documentsDirectory: /workspace\n" +
                "- insert:\n    - id: sugar-mobile-navigation\n      name: 'file://$plugin/index.js'\n" + diagnosticPolicy).toByteArray())
            val handoff = "/tmp/sugar-auth-" + UUID.randomUUID().toString().replace("-", "") + ".url"
            val timeZone = GuestTimeZone.prepare(context, client, id)
            val env = listOf("TZ=$timeZone", "PATH=/usr/bin:/bin", "HOME=/tmp/recovery-home", "DSH_HOME=/tmp/recovery-home/.dsh",
                "TMPDIR=/tmp", "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "SHELL=/bin/bash", "DSH_TELEMETRY_DISABLED=1",
                "NARB_DISABLE_NATIVE_CACHE=1", "SUGAR_HANDOFF=$handoff",
                "LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu",
                "SUCRUNE_WRITE_SCOPE=workspace-write", "SUCRUNE_MEMORY_POLICY=linux-jit",
                "SUCRUNE_NETWORK_POLICY=outbound", "SUCRUNE_NETWORK_ROUTE=system")
            progress(context.sugarString(R.string.splash_start_dsh))
            val argv = SugarRuntime.webCommand(context, client, id, patch, recovery = true)
            val execution = client.executePath(id, "/usr/bin/node", argv, env, cwd = "/workspace",
                resident = true, trustedExecutable = ProjectUserland.sandboxLauncher(context), workspaceDirectory = mount,
                directoryBind = GuestDirectoryBind(originalId, "/home/sugar/.dsh", RecoveryDirectory.profileMountPath))
            activeExecution = execution
            val login = SugarRuntime.awaitLogin(client, id, execution, handoff)
            client.writeFile(id, handoff, byteArrayOf())
            profileProject = id
            return login.toExternalForm()
        } catch (failure: Exception) {
            try { close(context) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    @Synchronized fun close(context: Context) {
        profileProject = null
        val client = RuntimeClient.get(context.applicationContext)
        try {
            try {
                activeExecution?.let { client.cancel(it); client.waitFor(it, 30_000) }
                activeImage?.park()
            } catch (failure: Exception) {
                // The cache is optional. Never publish a partial transfer or skip deletion of
                // a session's history and scratch directories when caching has failed.
                Log.w("SugarRecovery", "Could not keep the recovery image", failure)
            } finally {
                activeImage = null
                activeExecution = null
            }
            cleanOrphans(client)
        } finally { SugarRuntime.endMaintenance() }
    }

    /** Keep the temporary environment alive until this editor operation has returned. */
    @Synchronized fun <T> withProfile(project: String, block: () -> T): T {
        check(project == profileProject && activeExecution != null)
        return block()
    }

    @Synchronized fun restoreProgram(context: Context, progress: (String) -> Unit) {
        val client = RuntimeClient.get(context.applicationContext)
        try {
            SugarRuntime.beginMaintenance(context)
            cleanOrphans(client)
            val original = control(context, client)
            finishProgramRestore(original)
            val id = GuestImage.prepare(context, client, client.createEnvironment(recoveryName), progress)
            val pristine = GuestImage.root(context, id).resolve("opt/dsh")
            Files.move(pristine, original.parent.resolve("sugar-repair.pending"), ATOMIC_MOVE)
            finishProgramRestore(original)
        } finally { close(context) }
    }

    /** A short-lived publication journal outside the guest. After manager death, always
     * finish the verified pending rename before a normal host can start. */
    internal fun finishProgramRestore(original: Path) {
        val pending = original.parent.resolve("sugar-repair.pending")
        val old = original.parent.resolve("sugar-repair.replaced")
        if (Files.exists(pending, NOFOLLOW_LINKS)) {
            if (!Files.exists(original.resolve("opt"), NOFOLLOW_LINKS)) Files.createDirectory(original.resolve("opt"))
            ProjectTreeCopy.openSource(original, "/opt").use { dir ->
                val dst = Paths.get("/proc/self/fd/${dir.fd}/dsh")
                if (Files.exists(dst, NOFOLLOW_LINKS)) {
                    removeDetachedProgram(old)
                    Files.move(dst, old, ATOMIC_MOVE)
                }
                Files.move(pending, dst, ATOMIC_MOVE)
            }
        }
        // Publication has succeeded. A storage error during reclamation must not block
        // the restored host from starting. Retry on the next start.
        try { removeDetachedProgram(old) } catch (e: Exception) {
            Log.w("SugarRecovery", "Could not finish removing detached DSH program", e)
        }
    }

    private fun removeDetachedProgram(path: Path) {
        val stat = try { Os.lstat(path.toString()) } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return
            throw e
        }
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            // Linux O_PATH pins even a mode-000 directory. chmod acts on that descriptor;
            // a guest-created symlink cannot redirect it to a workspace or the new DSH.
            val oPath = 0x200000
            val flags = oPath or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or NativeProbe.directoryOpenFlag()
            val fd = Os.open(path.toString(), flags, 0)
            try { ParcelFileDescriptor.dup(fd).use { dir ->
                val pinned = Paths.get("/proc/self/fd/${dir.fd}")
                if (Os.fstat(dir.fileDescriptor).st_mode and 448 != 448) Os.chmod(pinned.toString(), 448)
                Files.newDirectoryStream(pinned).use { entries ->
                    for (entry in entries) removeDetachedProgram(entry)
                }
            } } finally { Os.close(fd) }
        }
        Files.delete(path)
    }

    @Synchronized fun resetConfiguration(context: Context) {
        val client = RuntimeClient.get(context.applicationContext)
        try {
            SugarRuntime.beginMaintenance(context)
            resetProfile(control(context, client))
        } finally { close(context) }
    }

    internal fun resetProfile(original: Path) {
        try {
            ProjectTreeCopy.openSource(original, profileDir).use { dir ->
                Files.deleteIfExists(Paths.get("/proc/self/fd/${dir.fd}/cordis.patch.yml"))
            }
        } catch (e: ErrnoException) { if (e.errno != OsConstants.ENOENT) throw e }
    }
}

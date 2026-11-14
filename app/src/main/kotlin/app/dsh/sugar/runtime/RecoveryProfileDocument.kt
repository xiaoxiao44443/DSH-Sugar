package app.dsh.sugar.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.*
import com.sucrune.probe.RuntimeClient
import java.io.DataInputStream

/** Only the main process opens a document. No path or program comes from the WebView. */
internal class RecoveryProfileDocument(private val context: Context, val project: String) {
    fun read(): ByteArray? = RecoverySession.withProfile(project) {
        val root = GuestImage.root(context,project)
        ProjectTreeCopy.openSource(root,PATH.substringBeforeLast('/')).use { dir ->
            val path = "/proc/self/fd/${dir.fd}/cordis.patch.yml"
            val fd = try { Os.open(path,O_RDONLY or O_NOFOLLOW or O_NONBLOCK or O_CLOEXEC,0) }
                catch (e: ErrnoException) { if (e.errno == ENOENT) return@withProfile null else throw e }
            try {
                val stat = Os.fstat(fd)
                require(S_ISREG(stat.st_mode) && stat.st_nlink == 1L && stat.st_size in 0L..1_048_576L)
                DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd))).use { input ->
                    ByteArray(stat.st_size.toInt()).also { bytes -> input.readFully(bytes); check(input.read() == -1) }
                }
            } finally { Os.close(fd) }
        }
    }

    fun save(revision: String, bytes: ByteArray): Int = RecoverySession.withProfile(project) {
        val client = RuntimeClient.get(context.applicationContext)
        SettingsDocumentPatch.recoverEditorLock(client,project,recovery = true)
        val helper = context.assets.open("guest/save-profile.mjs").bufferedReader().use { it.readText() }
        // Validate and publish inside the temporary guest. This worker gets no network,
        // external workspace mount or access to the repair targets in the resident host.
        val run = client.executePath(project, "/usr/bin/node",
            listOf("--input-type=module","--eval",helper,"--","save-profile",revision,"recovery"),
            env = listOf("HOME=/tmp/recovery-home","DSH_HOME=/tmp/recovery-home/.dsh","TMPDIR=/tmp",
                "PATH=/usr/bin:/bin","LANG=C.UTF-8","SUCRUNE_WRITE_SCOPE=workspace-write","SUCRUNE_MEMORY_POLICY=linux-jit",
                "LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu"),
            cwd = "/tmp", timeoutMs = 30_000, stdin = bytes)
        client.waitFor(run,35_000).optInt("exit_code",1)
    }

    companion object { const val PATH = "/tmp/recovery-home/.dsh/profiles/web/cordis.patch.yml" }
}

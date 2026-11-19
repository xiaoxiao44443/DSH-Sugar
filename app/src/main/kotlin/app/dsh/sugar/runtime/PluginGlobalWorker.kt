package app.dsh.sugar.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import com.sucrune.probe.GuestDirectory
import com.sucrune.probe.RuntimeClient
import org.json.JSONObject
import java.io.Closeable
import java.security.SecureRandom

/** Global plugin programs reuse the DSH image, with a separate execution that hides /workspace. */
internal class PluginGlobalWorker(
    context: Context,
    private val client: RuntimeClient,
    private val controlId: String
) : Closeable {
    private val app = context.applicationContext
    private data class Worker(val execution: String, val port: Int, val token: String)
    private var worker: Worker? = null
    private var closed = false

    @Synchronized fun endpoint(): JSONObject {
        check(!closed) { "插件后台进程服务已关闭。" }
        val timeZone = GuestTimeZone.prepare(app, client, controlId)
        val previous = worker
        if (previous != null && client.getExecution(previous.execution).optString("state") == "running") {
            return describe(previous, timeZone)
        }
        worker = null
        val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        val secret = password.toCharArray()
        val launcher = ProjectUserland.sandboxLauncher(app)
        val execution = try {
            client.executePath(controlId, "/usr/bin/node",
                listOf("/opt/sugar/plugin-global-worker.cjs", launcher.guestPath, "--sugar-plugin-global"),
                env = listOf(
                    "TZ=$timeZone", "HOME=/home/sugar", "DSH_HOME=/home/sugar/.dsh",
                    "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "PATH=/usr/bin:/bin", "SHELL=/bin/bash", "TMPDIR=/tmp",
                    "LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu",
                    "SUCRUNE_NETWORK_POLICY=outbound", "SUCRUNE_NETWORK_ROUTE=system",
                    "SUCRUNE_MEMORY_POLICY=linux-jit", "SUCRUNE_PROCESS_SCOPE=project"
                ),
                cwd = "/home/sugar", resident = true,
                secretEnv = mapOf("SUGAR_PLUGIN_GLOBAL_TOKEN" to secret),
                trustedExecutable = launcher, workspaceDirectory = NoWorkspaceDirectory)
        } finally {
            secret.fill('\u0000')
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 25_000
            var port: Int? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val status = client.getExecution(execution)
                check(status.getString("state") == "running") { "插件后台进程在就绪前退出。" }
                port = status.optString("stdout").lineSequence()
                    .firstOrNull { it.startsWith("SUGAR_PLUGIN_GLOBAL_PORT=") }
                    ?.substringAfter('=')?.toIntOrNull()?.takeIf { it in 1..65535 }
                if (port != null) break
                Thread.sleep(40)
            }
            val ready = Worker(execution, checkNotNull(port) { "插件后台进程启动超时。" }, password)
            worker = ready
            return describe(ready, timeZone)
        } catch (failure: Exception) {
            runCatching { client.cancel(execution) }
            throw failure
        }
    }

    private fun describe(ready: Worker, timeZone: String) = JSONObject()
        .put("url", "http://127.0.0.1:" + ready.port).put("token", ready.token)
        .put("global", true).put("writable", true)
        .put("root", "/home/sugar").put("id", "plugin-global").put("envId", controlId)
        .put("timeZone", timeZone)

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        worker?.let { runCatching { client.cancel(it.execution) } }
        worker = null
    }

    /**
     * Sucrune resolves /workspace through this execution-local provider before the
     * underlying control image. Descendants inherit the same provider. Returning
     * ENOENT at the mount root also prevents mkdir from recreating that root.
     */
    private object NoWorkspaceDirectory : GuestDirectory {
        private fun missing(operation: String): Nothing = throw ErrnoException(operation, OsConstants.ENOENT)
        private fun denied(operation: String): Nothing = throw ErrnoException(operation, OsConstants.EACCES)
        override fun stat(path: String): LongArray = missing("stat")
        override fun list(path: String): Array<String> = missing("readdir")
        override fun open(path: String, flags: Int, mode: Int): ParcelFileDescriptor {
            val writing = flags and OsConstants.O_ACCMODE != OsConstants.O_RDONLY ||
                flags and (OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_APPEND) != 0
            return if (writing) denied("open") else missing("open")
        }
        override fun mkdir(path: String): Unit = denied("mkdir")
        override fun remove(path: String, directory: Boolean): Unit = denied("remove")
        override fun rename(source: String, target: String, flags: Int): Unit = denied("rename")
    }
}

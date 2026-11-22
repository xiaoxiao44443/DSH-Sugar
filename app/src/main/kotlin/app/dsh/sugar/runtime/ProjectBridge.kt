package app.dsh.sugar.runtime

import app.dsh.sugar.WirelessAdb
import app.dsh.sugar.AgentNotifications
import app.dsh.sugar.DesktopPetActivity
import app.dsh.sugar.workspace.LocalFolderWorkspaces
import app.dsh.sugar.workspace.LocalDocumentDirectory
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.sucrune.probe.GuestDirectoryBind
import com.sucrune.probe.RuntimeClient
import com.sucrune.probe.RuntimeExceptionWithCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Private management channel for the authenticated DSH Host. Project workers receive separate credentials. */
internal class ProjectBridge(val context: Context, private val client: RuntimeClient, val controlId: String) : Closeable {
    val projects = ProjectSandboxes(context, client, controlId, ::isRegisteredRoot)
    private val key = token()
    private val globalPlugins = PluginGlobalWorker(context, client, controlId)
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val threads = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16))
    private data class Worker(val envId: String, val execution: String, val port: Int, val token: String,
                              val mount: LocalFolderWorkspaces.Binding?)
    private val workers = mutableMapOf<String, Worker>()
    @Volatile private var roots = emptyMap<String, String>()
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    val registryReady = CompletableFuture<Unit>()
    private class AdbShellFailure(cause: IOException) : IllegalStateException(cause.message, cause)
    private class FolderService(val port: Int, val token: String)
    @Volatile private var folderService: FolderService? = null

    fun inspectEmptyFolder(path: String): String = folderRequest("inspect", path).getString("ticket")

    fun removeEmptyFolder(path: String, ticket: String) {
        folderRequest("remove", path, ticket)
    }

    private fun folderRequest(op: String, path: String, ticket: String? = null): JSONObject =
        hostRequest("folder", JSONObject().put("op", op).put("path", path).putOpt("ticket", ticket), 4096)

    fun petReplyState(sessionId: String): JSONObject =
        hostRequest("pet-reply", JSONObject().put("op", "state").put("sessionId", sessionId))

    fun sessionStorage(request: JSONObject): JSONObject =
        hostRequest("session-storage", request, maxResult = 8 * 1024 * 1024, readTimeoutMs = 180_000)

    fun sendPetReply(sessionId: String, deliveryId: String, text: String?, questionId: String?, answers: JSONArray?): JSONObject =
        hostRequest("pet-reply", JSONObject().put("op", "send").put("sessionId", sessionId)
            .put("clientTimeZone", java.util.TimeZone.getDefault().id)
            .put("deliveryId", deliveryId).putOpt("text", text).putOpt("questionId", questionId).putOpt("answers", answers))

    fun petVoiceRequest(method: String, audioBase64: String? = null): JSONObject {
        require(method == "voiceAvailable" || method == "voiceTranscribe" || method == "voiceStatus")
        val body = JSONObject().put("op", method).putOpt("audioBase64", audioBase64)
        return hostRequest("pet-reply", body, readTimeoutMs = if (method == "voiceTranscribe") 45_000 else 15_000)
    }

    private fun hostRequest(endpoint: String, request: JSONObject, maxResult: Int = 65_536, readTimeoutMs: Int = 15_000): JSONObject {
        val service = checkNotNull(folderService) { "DSH 服务尚未就绪，请稍后重试。" }
        check(!closed) { "DSH 已重新启动，请重新打开。" }
        val data = request.toString().toByteArray(Charsets.UTF_8)
        val connection = URL("http://127.0.0.1:${service.port}/$endpoint").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(data.size)
            connection.setRequestProperty("Authorization", "Bearer ${service.token}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(data) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            checkNotNull(stream) { "DSH 未能完成操作，请稍后重试。" }
            val buffer = ByteArray(8192)
            val body = ByteArrayOutputStream()
            stream.use {
                while (true) {
                    val count = it.read(buffer)
                    if (count == -1) break
                    check(body.size() + count <= maxResult) { "DSH 返回了无效结果。" }
                    body.write(buffer, 0, count)
                }
            }
            val response = JSONObject(body.toString("UTF-8"))
            check(response.optBoolean("ok")) {
                response.optJSONObject("error")?.optString("message")?.take(240) ?: "操作失败。"
            }
            return response.getJSONObject("result")
        } finally { connection.disconnect() }
    }

    fun listen(): JSONObject {
        MobileUseTasks.initialize(context)
        AgentNotifications.clearPendingFromPreviousHost(context)
        DesktopPetActivity.attach(this)
        thread(name = "sugar-project-bridge", isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                try { threads.execute { socket.use { runCatching { serve(it) } } } }
                catch (_: RejectedExecutionException) { socket.close() }
            }
        }
        return JSONObject().put("url", "http://127.0.0.1:${listener.localPort}/workspace")
    }

    private val mobileScope = Any()
    private val adbCli = ProjectAdbCli(WirelessAdb.get(context), app.dsh.sugar.RootAccess.get(context), mobileScope, ::hasWorkspace)

    fun credential() = key.toCharArray()

    fun registeredRoot(id: String): String? = roots[id]

    fun hasWorkspace(id: String, path: String): Boolean = roots[id] == path

    private fun isRegisteredRoot(id: String, path: String): Boolean {
        val registered = roots
        return registered[id] == path && registered.none { (otherId, otherPath) ->
            otherId != id && isWorkspaceRoot(otherPath) &&
                (path == otherPath || path.startsWith("$otherPath/") || otherPath.startsWith("$path/"))
        }
    }

    fun workspace(id: String, root: String? = null): File {
        val registered = checkNotNull(roots[id]) { "DSH 尚未登记此工作区，请在加载完成后重试。" }
        require(root == null || registered == root) { "工作区路径已改变，请重新打开。" }
        return projects.ensure(id, registered).workspace
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5000
        val input = BufferedInputStream(socket.getInputStream())
        var total = 0
        fun line(): String {
            val content = ByteArrayOutputStream()
            while (++total <= 8192) {
                val next = input.read()
                require(next >= 0)
                if (next == 10) return content.toString("US-ASCII").removeSuffix("\r")
                content.write(next)
            }
            throw IllegalArgumentException("Header too large")
        }
        fun reply(status: String, data: JSONObject) {
            val bytes = data.toString().toByteArray()
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes); out.flush()
        }
        if (line() != "POST /workspace HTTP/1.1") { reply("404 Not Found", JSONObject()); return }
        val headers = mutableMapOf<String,String>()
        while (true) {
            val line = line()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            require(colon > 0 && headers.put(line.substring(0,colon).lowercase(), line.substring(colon+1).trim()) == null)
        }
        if (headers.containsKey("origin") || !MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(), "Bearer $key".toByteArray())) {
            reply("403 Forbidden", JSONObject()); return
        }
        val size = headers["content-length"]?.toIntOrNull() ?: -1
        if (size !in 1..32768 || headers.containsKey("transfer-encoding") ||
            headers["content-type"]?.substringBefore(';') != "application/json") { reply("400 Bad Request", JSONObject()); return }
        val data = ByteArray(size)
        java.io.DataInputStream(input).readFully(data)
        val result = try {
            JSONObject().put("ok", true).put("result", dispatch(JSONObject(data.toString(Charsets.UTF_8))))
        } catch (failure: Exception) {
            val code = when (failure) {
                is RuntimeExceptionWithCode -> failure.code
                is AdbShellFailure -> "adb_shell_error"
                else -> "workspace_error"
            }
            val detail = failure.localizedMessage?.takeIf {
                failure is IllegalStateException || failure is IllegalArgumentException
            }?.takeIf { "/data/" !in it && "/proc/" !in it && '\n' !in it }
            JSONObject().put("ok", false).put("error", JSONObject().put("code", code)
                .put("message", detail?.take(220) ?: if (failure is AdbShellFailure)
                    "ADB 命令未返回有效结果。" else "工作区操作失败（$code）。"))
        }
        reply("200 OK", result)
    }

    private fun dispatch(request: JSONObject): JSONObject {
        val op = request.getString("op")
        if (op == "pluginGlobalWorker") return globalPlugins.endpoint()
        if (op == "petState") {
            val result = request.optJSONObject("result")?.let {
                DesktopPetActivity.Result(it.getString("sessionId"), it.getString("id"),
                    it.getString("kind"), (it.opt("text") as? String)?.trim()?.takeIf(String::isNotBlank),
                    it.optLong("atMicros", 0L))
            }
            DesktopPetActivity.publish(this, request.getString("state"), request.opt("sessionId") as? String, result,
                request.opt("phase") as? String, request.opt("caption") as? String, request.opt("phoneStep") as? String,
                request.optLong("captionAt", 0L), request.optLong("turnStartedAt", 0L))
            return JSONObject()
        }
        if (op == "agentNotification" || op == "dismissAgentNotification") {
            check(!closed)
            AgentNotifications.receive(context, request, dismiss = op == "dismissAgentNotification")
            return JSONObject()
        }
        if (op == "adbCliReady") {
            return JSONObject().put("port", adbCli.register(request.getInt("port"), request.getString("token")))
        }
        if (op == "adbCliRevoke") {
            adbCli.revoke(request.getString("token"), request.optBoolean("rootOnly"))
            return JSONObject()
        }
        if (op == "pickerReady") {
            val port = request.getInt("port")
            val token = request.getString("token")
            require(port in 1..65535 && token.matches(Regex("[A-Za-z0-9_-]{43}")))
            folderService = FolderService(port, token)
            return JSONObject()
        }
        if (request.getString("op") == "registry") {
            val rows = request.getJSONArray("items")
            val next = HashMap<String,String>()
            for (i in 0 until rows.length()) {
                val entry = rows.optJSONArray(i)
                if (entry != null) {
                    rows.put(i, JSONObject().put("id", entry.getString(0)).put("root", entry.getString(1))
                        .put("title", entry.getString(2)).put("createdAt", entry.optLong(3)))
                }
                val row = rows.getJSONObject(i)
                require(next.put(row.getString("id"), row.getString("root")) == null)
            }
            val firstRegistry = synchronized(registryReady) {
                check(!closed) { "DSH 项目服务已关闭。" }
                projects.publishDocumentRegistry(rows, !registryReady.isDone, request.optString("settledRetainedId"))
                roots = next
                registryReady.complete(Unit)
            }
            if (firstRegistry) migrateIdleSharedTools(next)
            adbCli.prune()
            MobileUseTasks.remove(mobileScope) { hasWorkspace(it.project, it.root) }
            return JSONObject().put("count", rows.length())
        }
        if (op == "retainedWorkspace") return JSONObject().putOpt("id", projects.retainedId(request.getString("root")))
        if (op == "inspectRetainedFolder") return projects.retainedFolder(request.getString("root"))
        if (op == "forgetEmptyFolder") {
            projects.forgetEmptyFolder(request.getString("id"),request.getString("root"))
            return JSONObject()
        }
        val id = request.getString("id")
        val root = request.getString("root")
        return when (request.getString("op")) {
            "mobileTask" -> {
                check(!closed && hasWorkspace(id, root)) { "Workspace closed." }
                MobileUseTasks.control(MobileUseTasks.Owner(mobileScope,id,root,request.getString("conversation")),
                    request.optString("taskId"), request.getString("method"), request.optString("screen","ask"), request.optLong("turn",-1))
            }
            "status" -> JSONObject().put("bound", projects.lookup(id)?.root == root)
            "ensure" -> projects.ensure(id, root).let { JSONObject().put("id",id).put("envId", it.envId) }
            "worker" -> worker(id, root)
            "adbStatus", "adbShell" -> {
                check(hasWorkspace(id, root)) { "此对话的原工作区已移除。" }
                val adb = WirelessAdb.get(context)
                if (request.getString("op") == "adbStatus") {
                    val status = adb.status()
                    JSONObject().apply {
                        for (key in listOf("supported", "enabled", "paired", "connected", "busy"))
                            put(key, status.getBoolean(key))
                        put("phase", status.getString("phase"))
                        put("message", status.getString("message").take(220))
                    }
                } else {
                    val command = request.getString("command")
                    val inspection = AdbUiCommands.inspect(command)
                    inspection.requireWorkspaceCapture()
                    try {
                        val action = inspection.action
                        if (action == null) adb.shell(command) else {
                            val owner = MobileUseTasks.Owner(mobileScope, id, root, request.getString("conversation"))
                            MobileUseTasks.withAdbUi(owner, request.optJSONObject("route"), action, command) { routed ->
                                require(routed != "screencap -p") {
                                    "adb_shell 返回文本。请在工作区使用 adb exec-out screencap -p > screen.png 获取图片。"
                                }
                                adb.shell(routed)
                            }
                        }
                    } catch (failure: IOException) { throw AdbShellFailure(failure) }
                }
            }
            "exportBundle" -> JSONObject().put("path", ProjectBundleExport.publish(context, controlId, projects.ensure(id,root), request.getString("path")))
            "delete" -> { removeProject(id, root, request.optString("title")); JSONObject().put("deleted",true) }
            else -> throw IllegalArgumentException("Unknown workspace operation")
        }
    }

    private fun migrateIdleSharedTools(registered: Map<String, String>) {
        thread(name = "sugar-shared-tools", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            for ((id, root) in registered) {
                synchronized(this) {
                    if (closed) return@thread
                    if (roots[id] == root) {
                        try { projects.migrateIdleSharedTools(id, root) }
                        catch (e: Exception) {
                            android.util.Log.w("SugarProjects", "Shared tool migration will retry on project access", e)
                        }
                    }
                }
            }
        }
    }

    @Synchronized private fun worker(id: String, root: String): JSONObject {
        check(!closed && hasWorkspace(id, root)) { "此对话的原工作区已移除。" }
        val project = projects.ensure(id, root)
        val timeZone = GuestTimeZone.prepare(context, client, project.envId)
        val mount = LocalFolderWorkspaces.bindingFor(context, id, project.root)
        val previous = workers[id]
        if (previous != null && previous.mount != mount && client.getExecution(previous.execution).optString("state") == "running") {
            client.cancel(previous.execution)
            client.waitFor(previous.execution, 20_000)
        }
        val worker = if (previous != null && previous.mount == mount && client.getExecution(previous.execution).optString("state") == "running") previous else {
            SharedDshHome.prepareProject(project.workspace.toPath().parent)
            val password = token()
            val secret = password.toCharArray()
            val exec = try {
                val launcher = ProjectUserland.sandboxLauncher(context)
                client.executePath(project.envId, "/usr/bin/node", listOf("/opt/sugar/project-worker.cjs", launcher.guestPath),
                    env=listOf("TZ=$timeZone","HOME=/home/sugar","LANG=C.UTF-8","PATH=/usr/bin:/bin","SHELL=/bin/bash","TMPDIR=/tmp",
                        "LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu",
                        "SUCRUNE_NETWORK_POLICY=outbound", "SUCRUNE_NETWORK_ROUTE=system","SUCRUNE_MEMORY_POLICY=linux-jit","SUCRUNE_PROCESS_SCOPE=project"),
                    cwd="/workspace",resident=true,secretEnv=mapOf("SUGAR_PROJECT_TOKEN" to secret),
                    trustedExecutable=launcher,
                    workspaceDirectory=mount?.let { LocalDocumentDirectory(context, it.tree) },
                    directoryBind=GuestDirectoryBind(controlId, SharedDshHome.PATH, SharedDshHome.PATH))
            } finally { secret.fill('\u0000') }
            try {
                val deadline = SystemClock.elapsedRealtime()+25000
                var port: Int? = null
                while (SystemClock.elapsedRealtime() < deadline) {
                    val status=client.getExecution(exec)
                    check(status.getString("state")=="running") { "项目工作进程在就绪前退出。" }
                    port = status.optString("stdout").lineSequence().firstOrNull { it.startsWith("SUGAR_PROJECT_PORT=") }
                        ?.substringAfter('=')?.toIntOrNull()?.takeIf { it in 1..65535 }
                    if(port!=null) break
                    Thread.sleep(40)
                }
                Worker(project.envId,exec,checkNotNull(port) { "项目工作进程启动超时。" },password,mount).also { workers[id]=it }
            } catch (e: Exception) { client.cancel(exec); throw e }
        }
        return JSONObject().put("id",id).put("root", project.root).put("envId", worker.envId)
            .put("timeZone", timeZone)
            .put("url", "http://127.0.0.1:${worker.port}").put("token",worker.token)
    }

    @Synchronized private fun removeProject(id: String, root: String, title: String) {
        projects.lookup(id)?.let { require(it.root == root) }
        adbCli.remove(id)
        MobileUseTasks.remove(mobileScope) { it.project != id }
        workers[id]?.let {
            GuestTimeZone.forget(it.envId)
            if (client.getExecution(it.execution).optString("state") == "running") {
                client.cancel(it.execution)
                client.waitFor(it.execution, 20_000)
            }
        }
        projects.delete(id, root, title)
        LocalFolderWorkspaces.forget(context, id, root)
        workers.remove(id)
        roots = roots - id
    }

    override fun close() {
        DesktopPetActivity.detach(this)
        synchronized(registryReady) {
            closed = true
            registryReady.cancel(false)
        }
        listener.close()
        adbCli.close()
        globalPlugins.close()
        MobileUseTasks.remove(mobileScope) { false }
        threads.shutdownNow()
        synchronized(this) {
            workers.values.forEach {
                GuestTimeZone.forget(it.envId)
                runCatching { client.cancel(it.execution) }
            }
            workers.clear()
        }
    }

    private fun token() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
}

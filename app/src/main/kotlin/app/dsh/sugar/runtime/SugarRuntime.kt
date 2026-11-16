package app.dsh.sugar.runtime

import app.dsh.sugar.R
import app.dsh.sugar.RecoveryBrowserActivity
import app.dsh.sugar.sugarString
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import com.sucrune.probe.RuntimeClient
import com.sucrune.probe.RuntimeExceptionWithCode
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Process-wide coordinator. Activity recreation reuses the running Web server. */
object SugarRuntime {
    private class Server(val execution: String, var loginUrl: URL? = null)
    private class LocalLoginFailure(message: String) : IllegalStateException(message)
    private var server: Server? = null
    @Volatile internal var inMaintenance = false
        private set

    @Synchronized internal fun beginMaintenance(context: Context) {
        inMaintenance = true
        val client = RuntimeClient.get(context.applicationContext)
        server?.let {
            if (client.getExecution(it.execution).optString("state") == "running") stop(client, it.execution)
        }
        server = null
        activeProjects?.close()
        activeProjects = null
    }

    @Synchronized internal fun endMaintenance() { inMaintenance = false }
    @Volatile internal var activeProjects: ProjectBridge? = null
        private set
    private val loginPattern = Regex("""http://127\.0\.0\.1:([0-9]{1,5})/\?token=[A-Za-z0-9_-]{16,128}""")

    internal fun webCommand(context: Context, client: RuntimeClient, id: String, patch: String, recovery: Boolean): List<String> {
        val launcher = "/opt/sugar/web-host.cjs"
        context.assets.open("guest/web-host.cjs").use { client.writeFile(id, launcher, it.readBytes()) }
        return listOf(launcher, if (recovery) "recovery" else "main", "web",
            "--patch", patch, "--no-open", "--host", "127.0.0.1", "--port", "0")
    }

    /** Starts or reconnects to DSH and returns the resident execution to watch. */
    @Synchronized
    fun start(context: Context, onProgress: (String) -> Unit, onConnecting: () -> Unit, onReady: (String) -> Unit): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "DSH 需要在后台线程启动" }
        val app = context.applicationContext
        val client = RuntimeClient.get(app)
        check(!inMaintenance) { "DSH is in recovery mode" }
        RecoveryBrowserActivity.removeAbandonedProfiles(app)
        RecoverySession.cleanOrphans(client)
        server?.let { previous ->
            if (client.getExecution(previous.execution).getString("state") == "running") {
                previous.loginUrl?.let { url ->
                    onConnecting()
                    try { onReady(authorize(url)); return previous.execution }
                    catch (_: LocalLoginFailure) { previous.loginUrl = null }
                }
                stop(client, previous.execution)
            }
            server = null
        }
        activeProjects?.close()
        activeProjects = null
        onProgress(context.sugarString(R.string.splash_check_linux))
        val id = GuestImage.prepare(app, client, onProgress)
        val timeZone = GuestTimeZone.prepare(app, client, id)
        SettingsDocumentPatch.prepare(client, id)
        ProjectUserland.preparePackageManager(app, client, id)
        LegacyWorkspaceUi.restore(app, client, id)
        prepareResolver(client, id)
        for (dir in listOf("/home/sugar/.dsh", "/home/sugar/.cache/native", "/workspace", "/tmp")) {
            client.makeDirectory(id, dir)
        }
        SharedDshHome.prepareControl(GuestImage.root(app, id))
        // Refresh the built-in plugins when starting an already provisioned guest.
        for ((plugin, clientScripts) in mapOf(
            "dsh-sugar-workspace" to listOf("picker.js", "workspace-ui.js", "client.js"),
            "dsh-sugar-file-actions" to listOf("client.js"),
            "dsh-sugar-navigation" to listOf("client.js"),
            "dsh-sugar-notifications" to emptyList(),
            "dsh-sugar-adb" to emptyList(),
            "dsh-sugar-mobile-use" to listOf("client.js")
        )) {
            val pluginDir = "/opt/sugar/$plugin"
            client.makeDirectory(id, pluginDir)
            val hostFiles = if (plugin == "dsh-sugar-notifications")
                listOf("package.json", "index.js", "pet-replies.js", "pet-voice.js")
            else if (plugin == "dsh-sugar-adb") listOf("package.json", "index.js", "root-permissions.js")
            else listOf("package.json", "index.js")
            for (name in hostFiles) {
                app.assets.open("guest/$plugin/$name").use { client.writeFile(id, "$pluginDir/$name", it.readBytes()) }
            }
            if (plugin == "dsh-sugar-mobile-use") {
                app.assets.open("guest/mobile-use-guide.md").use { client.writeFile(id, "$pluginDir/guide.md", it.readBytes()) }
            }
            if (clientScripts.isEmpty()) continue
            val module = clientScripts.joinToString("\n", prefix = "(() => {\n", postfix = "\n})();\n") { name ->
                app.assets.open("guest/$plugin/$name").bufferedReader().use { it.readText() }
            }
            client.writeFile(id, "$pluginDir/client.js", module.toByteArray(Charsets.UTF_8))
        }
        val workspacePatch = "/opt/sugar/android-workspace.patch.yml"
        app.assets.open("guest/android-workspace.patch.yml").use {
            client.writeFile(id, workspacePatch, it.readBytes())
        }
        val handoff = "/tmp/sugar-auth-" + UUID.randomUUID().toString().replace("-", "") + ".url"
        val env = listOf(
            "TZ=$timeZone", "PATH=/usr/bin:/bin", "HOME=/home/sugar", "DSH_HOME=/home/sugar/.dsh",
            "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "TMPDIR=/tmp", "SHELL=/bin/bash",
            "NARB_NATIVE_CACHE_DIR=/home/sugar/.cache/native", "DSH_TELEMETRY_DISABLED=1",
            "LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu",
            "SUCRUNE_MEMORY_POLICY=linux-jit", "SUCRUNE_NETWORK_POLICY=outbound", "SUCRUNE_NETWORK_ROUTE=system", "SUGAR_HANDOFF=$handoff"
        )
        for (name in listOf("project-routing.cjs", "workspace-root.cjs", "adb-cli-sessions.cjs", "sugar-user-turn.cjs", "root-agents.mjs", "project-hooks.json", "project-creator-hooks.json", "project-process.cjs", "picker-folders.cjs", "workspace-directory-picker.mjs", "session-storage.cjs", "session-images.cjs")) {
            app.assets.open("guest/$name").use { client.writeFile(id, "/opt/sugar/$name", it.readBytes()) }
        }
        val argv = webCommand(app, client, id, workspacePatch, recovery = false)
        onProgress(context.sugarString(R.string.splash_start_dsh))
        val bridge = ProjectBridge(app, client, id)
        val token = bridge.credential()
        val execution = try {
            client.writeFile(id, "/opt/sugar/workspace-bridge.json", bridge.listen().toString().toByteArray())
            client.executePath(id, "/usr/bin/node", argv, env, cwd = "/workspace", resident = true,
                secretEnv = mapOf("SUGAR_MANAGEMENT_TOKEN" to token), trustedExecutable = ProjectUserland.sandboxLauncher(app))
        } catch (failure: Exception) {
            bridge.close()
            throw failure
        } finally { token.fill('\u0000') }
        activeProjects = bridge
        val launched = Server(execution)
        server = launched
        try {
            val login = awaitLogin(client, id, execution, handoff)
            // Erase the launch credential before doing network or WebView work.
            client.writeFile(id, handoff, byteArrayOf())
            onConnecting()
            val origin = authorize(login)
            launched.loginUrl = login
            // The browser can navigate while this thread removes the one-time handoff.
            onReady(origin)
        } catch (failure: Exception) {
            activeProjects = null
            bridge.close()
            try {
                stop(client, execution)
                server = null
            } catch (cleanup: Exception) {
                // Keep its ID so the next start can finish stopping the old worker.
                failure.addSuppressed(cleanup)
            }
            throw failure
        } finally {
            // Cleanup of the random handoff files cannot override a boot failure or a ready service.
            try {
                val clean = client.execute(id, "rm -f $handoff $handoff.tmp", timeoutMs = 10_000)
                client.waitFor(clean, 15_000)
            } catch (_: Exception) { /* already cleared on a successful handoff */ }
        }
        return execution
    }

    /** Wait without holding the startup lock. A replacement host owns a different bridge. */
    fun awaitExit(context: Context, execution: String) {
        RuntimeClient.get(context.applicationContext).waitFor(execution, Long.MAX_VALUE)
        synchronized(this) {
            if (server?.execution == execution) {
                val bridge = activeProjects
                server = null
                activeProjects = null
                bridge?.close()
            }
        }
    }

    private fun prepareResolver(client: RuntimeClient, id: String) {
        client.makeDirectory(id, "/etc")
        val entries = client.listFiles(id, "/etc").getJSONArray("entries")
        val existing = (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }.toSet()
        // Sucrune routes this guest address to the selected Android network's DNS resolver.
        val defaults = mapOf(
            "resolv.conf" to "nameserver 127.0.0.53\noptions timeout:2 attempts:2\n",
            "nsswitch.conf" to "hosts: files dns\n",
            "hosts" to "127.0.0.1 localhost\n::1 localhost\n"
        )
        for ((name, contents) in defaults) {
            if (name !in existing) client.writeFile(id, "/etc/$name", contents.toByteArray(Charsets.US_ASCII))
        }
    }

    private fun stop(client: RuntimeClient, execution: String) {
        client.cancel(execution)
        client.waitFor(execution, 20_000)
    }

    internal fun awaitLogin(client: RuntimeClient, id: String, execution: String, handoff: String): URL {
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val record = client.getExecution(execution)
            if (record.getString("state") != "running") {
                val code = record.optString("exit_code", record.getString("state"))
                val output = record.optString("stderr") + record.optString("stdout")
                val native = record.optJSONObject("result")?.optJSONObject("native")
                val known = listOf("ERR_MODULE_NOT_FOUND", "MODULE_NOT_FOUND", "ERR_DLOPEN_FAILED",
                    "EADDRINUSE", "EACCES", "ENOENT", "ENOSPC", "ENOSYS", "ENOMEM", "SIGSEGV")
                    .firstOrNull { output.contains(it) }
                val stage = native?.optString("stage")?.takeIf { it.matches(Regex("[a-z0-9_]{1,48}")) }
                val detail = listOfNotNull(code, known, stage).joinToString(" / ")
                throw IllegalStateException("DSH 在 WebUI 就绪前退出（$detail）")
            }
            val login = try { client.readFile(id, handoff).toString(Charsets.US_ASCII) }
            catch (failure: RuntimeExceptionWithCode) {
                if (failure.code != "not_found") throw failure
                null
            }
            if (login != null) {
                val match = loginPattern.matchEntire(login)
                check(match != null && match.groupValues[1].toInt() in 1..65535) { "DSH 返回了无效的本机地址" }
                return URL(login)
            }
            Thread.sleep(300)
        }
        throw IllegalStateException("等待 DSH WebUI 启动超时")
    }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 8_000
            useCaches = false
        }

    private fun authorize(login: URL): String {
        val origin = "http://127.0.0.1:${login.port}/"
        val currentName = LocalLoginCookies.nameForPort(login.port)
        val exchange = open(login)
        val cookie = try {
            if (exchange.responseCode != 303) throw LocalLoginFailure("DSH 拒绝了本地登录")
            val location = exchange.getHeaderField("Location")
            if (location == null || URL(login, location).toExternalForm() != origin)
                throw LocalLoginFailure("DSH 登录重定向无效")
            exchange.headerFields.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value?.firstOrNull { it.substringBefore('=') == currentName }
                ?: throw LocalLoginFailure("DSH 未返回登录凭据")
        } catch (_: java.io.IOException) {
            // Some URLConnection exceptions include the complete request URL and its bearer token.
            throw LocalLoginFailure("无法连接到 DSH 的本地登录服务")
        } finally { exchange.disconnect() }
        val page = open(URL(origin))
        try {
            page.setRequestProperty("Cookie", cookie.substringBefore(';'))
            if (page.responseCode != 200 || !page.contentType.orEmpty().startsWith("text/html", ignoreCase = true)) {
                throw LocalLoginFailure("DSH 首页尚未就绪")
            }
        } catch (_: java.io.IOException) {
            throw LocalLoginFailure("无法访问本地 DSH 首页")
        } finally { page.disconnect() }
        // setCookie's callback is delivered to the calling looper; never await it on main.
        val finished = CountDownLatch(1)
        var stored = false
        val deadline = SystemClock.elapsedRealtime() + 10_000
        Handler(Looper.getMainLooper()).post {
            try {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    finished.countDown()
                    return@post
                }
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                val stale = LocalLoginCookies.staleNames(cookies.getCookie(origin), currentName)
                var pending = stale.size + 1
                var accepted = true
                val completed = android.webkit.ValueCallback<Boolean> { success ->
                    accepted = accepted && success
                    if (--pending == 0) {
                        stored = accepted
                        finished.countDown()
                    }
                }
                // Queue the whole batch on this looper. Late callbacks never issue writes.
                for (name in stale) {
                    cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict", completed)
                }
                cookies.setCookie(origin, cookie, completed)
            } catch (_: RuntimeException) {
                finished.countDown()
            }
        }
        check(finished.await(10, TimeUnit.SECONDS) && stored) { "WebView 未能接收 DSH 登录凭据" }
        return origin
    }
}

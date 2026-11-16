package app.dsh.sugar

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The app's explicitly enabled su connection. Every invocation belongs to its caller's lifetime. */
internal class RootAccess private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("root-access", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sugar-root-check").apply { isDaemon = true } }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "sugar-root-deadline").apply { isDaemon = true } }
    private val listeners = mutableSetOf<() -> Unit>()
    private val operations = mutableSetOf<CancellationSignal>()
    @Volatile private var enabled = preferences.getBoolean("enabled", false)
    @Volatile private var available = false
    @Volatile private var busy = false
    @Volatile private var code = if (enabled) "disconnected" else "disabled"
    private var generation = 0L

    @Synchronized fun status(): JSONObject = JSONObject().put("enabled", enabled).put("available", isAvailable())
        .put("busy", busy).put("code", code).put("message", when (code) {
            "disabled" -> "Root 未启用"
            "checking" -> "等待 Root 管理器授权"
            "ready" -> "Root 已连接"
            "timeout" -> "Root 授权检查超时"
            "disconnected" -> "Root 连接已结束，请重新检查授权"
            else -> "Root 不可用，请在管理器中允许 Sugar"
        })

    fun isAvailable() = enabled && available
    fun addListener(listener: () -> Unit) { synchronized(listeners) { listeners.add(listener) } }
    fun removeListener(listener: () -> Unit) { synchronized(listeners) { listeners.remove(listener) } }
    private fun changed() { main.post { synchronized(listeners) { listeners.toList() }.forEach { runCatching(it) } } }
    fun mobileConnectionFailed() { unavailable() }

    fun setEnabled(value: Boolean) {
        val cancel = synchronized(this) {
            enabled = value
            preferences.edit().putBoolean("enabled", value).apply()
            if (value) emptyList() else {
                generation++
                available = false
                busy = false
                code = "disabled"
                operations.toList()
            }
        }
        cancel.forEach { it.cancel() }
        changed()
        if (value) probe()
    }

    fun probe() {
        val epoch = synchronized(this) {
            if (!enabled || busy) return
            busy = true
            code = "checking"
            generation
        }
        changed()
        worker.execute {
            val signal = CancellationSignal()
            val timeout = AtomicBoolean()
            val deadline = deadlines.schedule({ timeout.set(true); signal.cancel() }, 30, TimeUnit.SECONDS)
            val success = runCatching {
                execOut("id -u", ByteArrayOutputStream(), ByteArrayOutputStream(), signal) == 0
            }.getOrDefault(false)
            deadline.cancel(false)
            synchronized(this) {
                if (epoch != generation || !enabled) return@execute
                busy = false
                available = success
                code = if (success) "ready" else if (timeout.get()) "timeout" else "unavailable"
            }
            changed()
        }
    }

    private fun unavailable() {
        synchronized(this) {
            available = false
            if (enabled && !busy) code = "unavailable"
        }
        changed()
    }

    /** Commands get a separate process group. Closing the control pipe reaps its descendants as root. */
    fun execOut(command: String, stdout: OutputStream, stderr: OutputStream, signal: CancellationSignal?): Int {
        require(command.indexOf('\u0000') < 0) { "Root command contains NUL." }
        val marker = "SUGAR_ROOT_${UUID.randomUUID()}"
        val script = """
            if [ "${'$'}(/system/bin/id -u)" != 0 ]; then exit 126; fi
            printf '%s\n' ${quote(marker)}
            exec 3<&0
            /system/bin/setsid /system/bin/sh -c ${quote(command)} </dev/null &
            sugar_child=${'$'}!
            ( read -r sugar_control; kill -TERM -- -${'$'}sugar_child 2>/dev/null; sleep 0.2; kill -KILL -- -${'$'}sugar_child 2>/dev/null ) <&3 &
            sugar_watcher=${'$'}!
            wait ${'$'}sugar_child
            sugar_exit=${'$'}?
            kill -TERM -- -${'$'}sugar_child 2>/dev/null
            sleep 0.2
            kill -KILL -- -${'$'}sugar_child 2>/dev/null
            kill ${'$'}sugar_watcher 2>/dev/null
            wait ${'$'}sugar_watcher 2>/dev/null
            exit ${'$'}sugar_exit
        """.trimIndent()
        return withProcess(script, signal) { process, cancel ->
            val errorThread = thread(isDaemon = true, name = "sugar-root-stderr") {
                runCatching { process.errorStream.copyTo(stderr) }
            }
            try {
                readMarker(process.inputStream, marker, cancel)
                process.inputStream.copyTo(stdout)
                val exit = process.waitFor()
                errorThread.join(2000)
                signal?.throwIfCanceled()
                exit
            } finally { runCatching { process.outputStream.close() } }
        }
    }

    /** Packaged helper consumes its own framed stdin. EOF closes all displays and its UiAutomation. */
    fun <T> withMobileHelper(apk: String, signal: CancellationSignal, action: (InputStream, OutputStream) -> T): T {
        require(apk.startsWith('/') && apk.none { it == '\u0000' || it == '\n' })
        val command = "CLASSPATH=${quote(apk)} exec /system/bin/app_process / app.dsh.sugar.mobile.shell.VirtualAgent"
        return withProcess(command, signal) { process, _ ->
            thread(isDaemon = true, name = "sugar-root-mobile-stderr") {
                runCatching { process.errorStream.copyTo(object : OutputStream() { override fun write(value: Int) {} }) }
            }
            action(process.inputStream, process.outputStream)
        }
    }

    private fun readMarker(input: InputStream, marker: String, cancel: CancellationSignal) {
        val deadline = deadlines.schedule({ cancel.cancel() }, 30, TimeUnit.SECONDS)
        try {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= 128) {
                val next = input.read()
                if (next < 0) throw IOException("Root authorization was denied or expired.")
                if (next == 10) break
                bytes.write(next)
            }
            if (bytes.toString("UTF-8").trimEnd('\r') != marker) throw IOException("Root authorization could not be verified.")
        } catch (failure: Exception) {
            unavailable()
            throw failure
        } finally { deadline.cancel(false) }
    }

    private fun <T> withProcess(command: String, signal: CancellationSignal?, action: (Process, CancellationSignal) -> T): T {
        val cancel = CancellationSignal()
        synchronized(this) {
            check(enabled) { "Root is disabled in Sugar." }
            signal?.throwIfCanceled()
            operations.add(cancel)
        }
        var process: Process? = null
        try {
            signal?.setOnCancelListener { cancel.cancel() }
            cancel.throwIfCanceled()
            process = try { ProcessBuilder("su", "-c", command).start() } catch (failure: Exception) {
                unavailable()
                throw IOException("Root is unavailable. Allow Sugar in your root manager.", failure)
            }
            val running = process
            cancel.setOnCancelListener {
                runCatching { running.outputStream.close() }
                // The root-side watcher needs time to terminate the command's entire process group.
                deadlines.schedule({ if (running.isAlive) running.destroy() }, 800, TimeUnit.MILLISECONDS)
                deadlines.schedule({
                    if (running.isAlive) running.destroyForcibly()
                    runCatching { running.inputStream.close() }
                    runCatching { running.errorStream.close() }
                }, 1800, TimeUnit.MILLISECONDS)
            }
            cancel.throwIfCanceled()
            return action(running, cancel)
        } finally {
            signal?.setOnCancelListener(null)
            cancel.cancel()
            runCatching { process?.outputStream?.close() }
            process?.let { running ->
                runCatching { if (!running.waitFor(1000, TimeUnit.MILLISECONDS)) running.destroy() }
                runCatching { running.inputStream.close() }
                runCatching { running.errorStream.close() }
            }
            synchronized(this) { operations.remove(cancel) }
        }
    }

    companion object {
        @Volatile private var instance: RootAccess? = null
        fun get(context: Context): RootAccess = instance ?: synchronized(this) {
            instance ?: RootAccess(context.applicationContext).also { instance = it; if (it.enabled) it.probe() }
        }
        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}

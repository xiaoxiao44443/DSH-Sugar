package app.dsh.sugar.mobile

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import app.dsh.sugar.WirelessAdb
import app.dsh.sugar.RootAccess
import app.dsh.sugar.runtime.MobileUseTasks
import io.github.muntashirakon.adb.WirelessAdbTransport
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Tasks keep their original backend. One helper shares Android's UiAutomation registration. */
internal object VirtualScreen {
    private val lock = Any()
    private val sessions = mutableMapOf<String, Session>()
    private var current: Link? = null
    private val cleanup = Executors.newCachedThreadPool { Thread(it, "sugar-virtual-stop").apply { isDaemon = true } }
    private val operations = setOf("observe","preview","check_foreground","tap","long_press","swipe","drag","input","launch","open_app","home","back","recents")
    private class Session(val id: String, val link: Link, val main: Boolean) {
        val wireId: String = UUID.randomUUID().toString()
        val revoked = AtomicBoolean()
        val opened = CompletableFuture<JSONObject>()
        val sendLock = Any()
        var openSent = false
    }

    private class Channel(val input: InputStream, val write: (ByteArray) -> Unit, val awaitNextPacket: () -> Unit = {})
    private class Link(val adb: WirelessAdb, val transport: WirelessAdbTransport?, val root: RootAccess?, val apk: String) {
        val ended = AtomicBoolean()
        val ready = CompletableFuture<Channel>()
        private val signal = CancellationSignal()
        private val sendLock = Any()
        private val replies = VirtualDisplayReplies<JSONObject>()

        fun start() = thread(isDaemon = true, name = "sugar-virtual-reader") {
            try {
                if (root != null) root.withMobileHelper(apk, signal) { input, output ->
                    readReplies(Channel(input, { bytes -> output.write(bytes); output.flush() }))
                } else adb.withConnection(checkNotNull(transport), ::end) { connection ->
                    connection.virtualDisplayHelper(apk, signal) { channel ->
                        readReplies(Channel(channel, { bytes -> channel.write(bytes); Unit }, { channel.awaitNextPacket() }))
                    }
                }
            } catch (failure: Exception) {
                if (!signal.isCanceled) root?.mobileConnectionFailed()
                ready.completeExceptionally(failure)
            } finally { end() }
        }

        private fun readReplies(channel: Channel) {
            val hello = read(channel.input)
            if (hello.optInt("protocol") != 2) throw IOException("Incompatible screen helper.")
            if (root != null && hello.optInt("runtime_uid", -1) != 0) throw IOException("Root identity could not be verified.")
            ready.complete(channel)
            while (!ended.get()) {
                channel.awaitNextPacket()
                val reply = read(channel.input)
                if (reply.optString("event") == "removed") removed(this, reply.getString("task"))
                else replies.complete(reply.getLong("request"), reply.getJSONObject("result"))
            }
        }

        fun end() {
            if (!ended.compareAndSet(false,true)) return
            val failure = IOException("The virtual display connection ended.")
            ready.completeExceptionally(failure)
            replies.close(failure)
            signal.cancel()
            synchronized(lock) {
                if (current === this) current = null
                sessions.entries.removeAll { (_, session) ->
                    if (session.link !== this) false else {
                        session.revoked.set(true)
                        session.opened.complete(closed())
                        true
                    }
                }
            }
            MobileUseTasks.deviceChanged("virtual")
            MobileUseTasks.deviceChanged("main")
        }

        /** Only the short frame write is serialized. Each display waits on its own response. */
        fun submit(session: Session, op: String, args: JSONObject, allowed: () -> Boolean): CompletableFuture<JSONObject> {
            val channel = ready.get(18, TimeUnit.SECONDS)
            synchronized(sendLock) {
                if (ended.get() || !allowed()) return CompletableFuture.completedFuture(closed())
                val ticket = replies.begin()
                try {
                    val bytes = JSONObject().put("request", ticket.id).put("task", session.wireId).put("op", op)
                        .put("args", args).toString().toByteArray(Charsets.UTF_8)
                    if (bytes.size > 65536) replies.complete(ticket.id, screenError("invalid_argument","Request is too large."))
                    else channel.write(ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array())
                } catch (failure: Exception) {
                    end()
                    throw failure
                }
                return ticket.result
            }
        }
    }

    fun open(context: Context, id: String, expected: WirelessAdbTransport?, allowed: () -> Boolean, main: Boolean = false): JSONObject {
        if (Build.VERSION.SDK_INT < if (main) 30 else 34)
            return screenError("unsupported", if (main) "Root main screen control requires Android 11 or later." else "Android 14 or later is required.")
        val adb = WirelessAdb.get(context)
        val root = RootAccess.get(context).takeIf { expected == null && it.isAvailable() }
        var first = false
        val session = synchronized(lock) {
            if (!allowed()) return closed()
            sessions[id]?.let { return@synchronized it }
            val existing = current?.takeIf { !it.ended.get() }
            if (main && existing != null && existing.root == null)
                return screenError("screen_busy", "Close the existing ADB virtual screens before using Root main screen control.")
            if (existing == null && root == null && (expected == null || adb.currentTransport() !== expected))
                return screenError("connection_unavailable","Connect Root or wireless ADB in Sugar.")
            if (main && root == null && existing?.root == null)
                return screenError("root_unavailable","Root main screen control is unavailable.")
            if (main && sessions.values.any { it.main && !it.revoked.get() })
                return screenError("screen_busy", "Another session is using the main display.")
            val link = existing ?: Link(adb, expected, root, context.applicationInfo.sourceDir).also { current = it; it.start() }
            first = true
            Session(id, link, main).also { sessions[id] = it }
        }
        if (first) {
            try {
                val reply = synchronized(session.sendLock) {
                    session.link.submit(session, "open", JSONObject().put("main", main)) { !session.revoked.get() && allowed() }
                        .also { session.openSent = true }
                }
                session.opened.complete(await(reply))
            } catch (failure: Exception) {
                if (allowed() && (!session.link.ready.isDone || session.link.ready.isCompletedExceptionally))
                    session.link.root?.mobileConnectionFailed()
                session.link.end()
                session.opened.complete(screenError("virtual_display_unavailable", failure.cause?.message ?: failure.message ?: "Virtual display startup failed."))
            }
        }
        val result = session.opened.get()
        if (!result.optBoolean("ok")) closeSession(session)
        return if (session.revoked.get() && result.optBoolean("ok")) closed() else result
    }

    private fun await(future: CompletableFuture<JSONObject>): JSONObject = try {
        future.get(20, TimeUnit.SECONDS)
    } finally {
        if (!future.isDone) future.cancel(false)
    }
    private fun closed() = screenError("session_closed","The virtual display is no longer active.")

    fun isOpen(id: String): Boolean {
        val session = synchronized(lock) { sessions[id] } ?: return false
        return !session.revoked.get() && !session.link.ended.get() &&
            (session.link.root?.status()?.optBoolean("enabled") ?: (session.link.adb.currentTransport() === session.link.transport))
    }
    fun close(id: String) { synchronized(lock) { sessions[id] }?.let(::closeSession) }

    private fun closeSession(session: Session) {
        if (!session.revoked.compareAndSet(false,true)) return
        session.opened.complete(closed())
        cleanup.execute {
            try {
                val response = synchronized(session.sendLock) {
                    if (session.openSent && !session.link.ended.get())
                        session.link.submit(session, "close", JSONObject()) { true }
                    else null
                }
                response?.let(::await)
            } catch (_: Exception) {
                // A lost transport releases its displays via Binder. Never revoke an unrelated generation.
            } finally { forget(session) }
        }
    }

    private fun removed(link: Link, wireId: String) {
        val session = synchronized(lock) { sessions.values.find { it.link === link && it.wireId == wireId } } ?: return
        session.revoked.set(true)
        session.opened.complete(closed())
        forget(session)
        MobileUseTasks.deviceChanged("virtual")
        MobileUseTasks.deviceChanged("main")
    }
    private fun forget(session: Session) {
        val retire = synchronized(lock) {
            if (sessions[session.id] === session) sessions.remove(session.id)
            if (sessions.values.none { it.link === session.link }) {
                if (current === session.link) current = null
                true
            } else false
        }
        if (retire) session.link.end()
    }

    fun preview(id: String) = execute(id,"preview",JSONObject()) { true }

    fun execute(id: String, op: String, args: JSONObject, allowed: () -> Boolean): JSONObject {
        if (op !in operations) return screenError("unsupported","Unknown virtual screen operation.")
        val session = synchronized(lock) { sessions[id] } ?: return closed()
        return try {
            val response = synchronized(session.sendLock) {
                session.link.submit(session, op, args) { !session.revoked.get() && allowed() }
            }
            val result = await(response)
            if (session.revoked.get() || !allowed()) closed().put("dispatch","unknown").put("verification","unknown")
            else result
        } catch (_: Exception) {
            if (op == "preview") return screenError("preview_unavailable","The preview frame was not available.")
            val failure = if (isOpen(id)) {
                screenError("reply_unavailable","Virtual display command lost its reply. Observe the screen before the next action.")
            } else {
                MobileUseTasks.deviceChanged("virtual")
                MobileUseTasks.deviceChanged("main")
                screenError("connection_lost","The virtual display connection ended.")
            }
            failure.put("dispatch","unknown").put("verification","unknown")
        }
    }

    private fun read(channel: InputStream): JSONObject {
        val input = DataInputStream(channel)
        val size = input.readInt()
        if (size !in 2..8*1024*1024) throw IOException("Invalid response from virtual display helper.")
        return JSONObject(ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8))
    }
}

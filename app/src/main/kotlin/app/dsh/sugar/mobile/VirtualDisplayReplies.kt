package app.dsh.sugar.mobile

import java.io.IOException
import java.util.concurrent.CompletableFuture

/** Response IDs are local to one authenticated shell stream. A late reply never crosses connections. */
internal class VirtualDisplayReplies<T> {
    data class Ticket<T>(val id: Long, val result: CompletableFuture<T>)
    private var sequence = 0L
    private var closed: IOException? = null
    private val pending = mutableMapOf<Long, CompletableFuture<T>>()

    @Synchronized fun begin(): Ticket<T> {
        closed?.let { throw it }
        val id = ++sequence
        val result = CompletableFuture<T>()
        pending[id] = result
        result.whenComplete { _, _ -> synchronized(this) { pending.remove(id) } }
        return Ticket(id,result)
    }

    fun complete(id: Long, value: T) {
        val result = synchronized(this) { pending.remove(id) }
        result?.complete(value)
    }

    fun close(reason: IOException) {
        val outstanding = synchronized(this) {
            if (closed != null) return
            closed = reason
            pending.values.toList().also { pending.clear() }
        }
        outstanding.forEach { it.completeExceptionally(reason) }
    }
}

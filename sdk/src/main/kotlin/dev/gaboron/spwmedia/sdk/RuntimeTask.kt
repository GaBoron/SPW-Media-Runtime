package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** A cancellable managed invocation. Subscribe for progress; [result] always reaches one terminal outcome. */
class RuntimeTask<T> internal constructor(
    val id: String, private val client: MediaRuntime, private val executor: ScheduledExecutorService,
    private val decode: (JsonElement) -> T, private val finished: (RuntimeTask<T>) -> Unit
) {
    private val completion = CompletableFuture<T>()
    private val publisher = SubmissionPublisher<TaskSnapshot>(executor, 16)
    private var polling: ScheduledFuture<*>? = null
    private val completed = AtomicBoolean()
    /** Read-only completion with MediaRuntimeException failures. Cancelling a toCompletableFuture view does not change this result or cancel the backend; use cancel(). */
    val result: CompletionStage<T> = completion.minimalCompletionStage()
    /** Bounded best-effort progress events; slow subscribers may lose intermediate progress. Query [snapshot] for current state. */
    val events: Flow.Publisher<TaskSnapshot> get() = publisher
    /** Obtain an immutable runtime snapshot. */
    fun snapshot(): TaskSnapshot = Protocol.json.decodeFromJsonElement(client.exchange("task", payload = obj("id" to id)))
    /** Requests cancellation of this managed task, including its process tree. */
    fun cancel(): Boolean = client.exchange("cancel", payload = obj("id" to id)).jsonPrimitive.boolean
    internal fun start() {
        polling = executor.scheduleWithFixedDelay({
            if (completion.isDone) return@scheduleWithFixedDelay
            try {
                val state = snapshot()
                publisher.offer(state) { _, _ -> false }
                when (state.state) {
                    "SUCCEEDED" -> complete { completion.complete(decode(state.result)) }
                    "FAILED", "CANCELLED", "TIMED_OUT" -> complete { completion.completeExceptionally(MediaRuntimeException(state.error ?: RuntimeError(state.state, "Task ${state.state.lowercase()}", operationId = id))) }
                }
            } catch (e: Throwable) { complete { completion.completeExceptionally(e) } }
        }, 25, 100, TimeUnit.MILLISECONDS)
    }
    private fun complete(action: () -> Unit) {
        if (!completed.compareAndSet(false, true)) return
        try { action() } catch (error: Throwable) { completion.completeExceptionally(error) }
        finally { polling?.cancel(false); publisher.close(); finished(this) }
    }
    internal fun disconnected() = complete { completion.completeExceptionally(MediaRuntimeException(RuntimeError("CLIENT_CLOSED", "Connection closed", operationId = id))) }
}

/** Owned long-lived resource. Explicit close is required before the connection closes. */
open class MediaSession internal constructor(val id: String, val capability: String, protected val client: MediaRuntime) : AutoCloseable {
    private val resource = RemoteResource(id, client, 20)
    /** Current session state and structured backend failure. */
    fun snapshot(): SessionSnapshot { resource.checkOpen(); return Protocol.json.decodeFromJsonElement(client.exchange("sessionState", payload = obj("id" to id))) }
    /** Poll-backed state events. There is no polling until a subscriber attaches. */
    val events: Flow.Publisher<SessionSnapshot> get() = client.watch(this)
    /** Releases only this session; repeated close is harmless. */
    override fun close() = resource.close()
    internal fun isClosed() = resource.isClosed()
}

internal class SessionEvents(private val session: MediaSession, private val executor: ScheduledExecutorService, private val client: MediaRuntime) : Flow.Publisher<SessionSnapshot> {
    override fun subscribe(subscriber: Flow.Subscriber<in SessionSnapshot>) {
        val demand = java.util.concurrent.atomic.AtomicLong()
        val stopped = AtomicBoolean()
        var future: ScheduledFuture<*>? = null
        val cleanup = AutoCloseable { if (stopped.compareAndSet(false, true)) {
            future?.cancel(false)
            try { executor.execute { subscriber.onComplete() } }
            catch (_: RejectedExecutionException) { ForkJoinPool.commonPool().execute { subscriber.onComplete() } }
        } }
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) {
                if (n <= 0) { cancel(); subscriber.onError(IllegalArgumentException("Demand must be positive")); return }
                demand.getAndUpdate { if (Long.MAX_VALUE - it < n) Long.MAX_VALUE else it + n }
            }
            override fun cancel() { stopped.set(true); future?.cancel(false); client.forget(cleanup) }
        })
        if (stopped.get()) return
        try { client.own(cleanup) } catch (_: MediaRuntimeException) { return }
        try { future = executor.scheduleWithFixedDelay({
            if (stopped.get()) return@scheduleWithFixedDelay
            try {
                if (session.isClosed()) { cleanup.close(); client.forget(cleanup) }
                else if (demand.get() > 0) { demand.decrementAndGet(); subscriber.onNext(session.snapshot()) }
            } catch (e: Throwable) { stopped.set(true); future?.cancel(false); client.forget(cleanup); subscriber.onError(e) }
        }, 25, 100, TimeUnit.MILLISECONDS) }
        catch (_: RejectedExecutionException) { cleanup.close(); client.forget(cleanup) }
        if (stopped.get()) future?.cancel(false)
    }
}

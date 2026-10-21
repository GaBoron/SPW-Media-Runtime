package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.RuntimeError
import java.util.concurrent.*
import java.util.function.Function

/** Bounded recovery timing, in milliseconds. All values must be positive. Since 0.2.0. */
data class ConnectionOptions @JvmOverloads constructor(
    val retryTimeoutMillis: Long = 30_000, val retryIntervalMillis: Long = 1_000,
    val healthCheckIntervalMillis: Long = 30_000, val cleanupTimeoutMillis: Long = 5_000
) {
    init { require(listOf(retryTimeoutMillis, retryIntervalMillis, healthCheckIntervalMillis, cleanupTimeoutMillis).all { it > 0 }) }
}

/** WAITING retries within one bounded round; UNAVAILABLE requires explicit reconnect. Since 0.2.0. */
enum class ConnectionStatus { WAITING, CONNECTED, UNAVAILABLE, CLOSED }

/** Generation increases only after a successful new connection. [error] never contains transport credentials. */
data class ConnectionSnapshot(val status: ConnectionStatus, val generation: Long, val error: RuntimeError? = null)

/**
 * Optional plugin-scoped connection recovery. Created by [MediaRuntime.connectWhenAvailable].
 * Does not replay tasks or restore sessions. Recreate media explicitly for the new [snapshot] generation.
 * [events] is bounded/best effort; [snapshot] is authoritative. Callbacks never run on the UI thread.
 * Cleanup stops caller-owned processes before the old client releases leases. Failed/timed-out cleanup
 * suspends recovery and conservatively abandons executable pins. Call [reconnect] after fixing cleanup.
 * Close from plugin stop; close may wait up to the configured cleanup deadline and can throw CLEANUP_FAILED.
 */
class RuntimeConnection internal constructor(
    private val pluginId: String, private val options: ConnectionOptions,
    private val beforeDisconnect: Function<MediaRuntime, CompletionStage<Void>>
) : AutoCloseable {
    private val scheduler = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "SPW Media connection").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val callbacks = Executors.newSingleThreadExecutor { r -> Thread(r, "SPW Media cleanup").apply { isDaemon = true } }
    private val publisher = SubmissionPublisher<ConnectionSnapshot>(ForkJoinPool.commonPool(), 16)
    private val lock = Any()
    private var stopped = false
    private var revision = 0L
    private var cleanup: CompletableFuture<Void>? = null
    private var cleanupFor: MediaRuntime? = null
    private var cleanupClient: MediaRuntime? = null
    private var pending: CompletableFuture<MediaRuntime>? = null
    private var scheduled: ScheduledFuture<*>? = null
    /** The currently usable client, or null while unavailable/recovering. Owned by this component. */
    @Volatile var current: MediaRuntime? = null
        private set
    /** Latest state. Available without subscribing or blocking. */
    @Volatile var snapshot = ConnectionSnapshot(ConnectionStatus.WAITING, 0)
        private set
    /** Background state notifications; subscribe then read [snapshot] to obtain the initial state. */
    val events: Flow.Publisher<ConnectionSnapshot> get() = publisher

    init { reconnect() }

    /** Starts a fresh bounded round, coalescing concurrent calls. Never submits media work. */
    fun reconnect(): CompletionStage<MediaRuntime> = synchronized(lock) {
        if (stopped) return@synchronized CompletableFuture.failedFuture<MediaRuntime>(closedError()).minimalCompletionStage()
        pending?.takeUnless { it.isDone }?.let { return@synchronized it.minimalCompletionStage() }
        val result = CompletableFuture<MediaRuntime>()
        pending = result
        val attempt = ++revision
        scheduled?.cancel(false)
        current = null
        val previous = cleanupClient
        change(ConnectionStatus.WAITING)
        beginCleanup(previous).whenComplete { _, error -> enqueue {
            synchronized(lock) {
                if (stopped || attempt != revision) return@synchronized
                if (error != null) unavailable(result, cleanupError(error))
                else {
                    cleanupClient = null
                    connectAttempt(attempt, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.retryTimeoutMillis), result)
                }
            }
        } }
        result.minimalCompletionStage()
    }

    private fun connectAttempt(attempt: Long, deadline: Long, result: CompletableFuture<MediaRuntime>) {
        if (stopped || attempt != revision) return
        try {
            val client = MediaRuntime.connect(pluginId)
            current = client
            cleanupClient = client
            change(ConnectionStatus.CONNECTED, generation = snapshot.generation + 1)
            result.complete(client)
            scheduled = scheduler.schedule({ health(attempt) }, options.healthCheckIntervalMillis, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            val failure = MediaRuntimeException.unwrap(error) ?: MediaRuntimeException(RuntimeError("CONNECTION_FAILED", "Runtime connection failed"))
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (failure.detail.code in setOf("RUNTIME_UNAVAILABLE", "TRANSPORT_FAILED") && remaining > 0) {
                change(ConnectionStatus.WAITING, failure.detail)
                scheduled = scheduler.schedule({ synchronized(lock) { connectAttempt(attempt, deadline, result) } }, minOf(options.retryIntervalMillis, remaining.coerceAtLeast(1)), TimeUnit.MILLISECONDS)
            } else unavailable(result, failure)
        }
    }

    private fun health(attempt: Long): Unit = synchronized(lock) {
        if (stopped || attempt != revision) return@synchronized
        try {
            current?.capabilities()
            scheduled = scheduler.schedule({ health(attempt) }, options.healthCheckIntervalMillis, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            val failure = MediaRuntimeException.unwrap(error)
            if (failure?.detail?.code in setOf("RUNTIME_UNAVAILABLE", "CLIENT_CLOSED", "TRANSPORT_FAILED")) reconnect()
            else { current = null; change(ConnectionStatus.UNAVAILABLE, failure?.detail ?: RuntimeError("CONNECTION_FAILED", "Runtime health check failed")) }
        }
    }

    private fun beginCleanup(client: MediaRuntime?): CompletableFuture<Void> {
        if (client == null) return CompletableFuture.completedFuture(null)
        cleanup?.let { if (cleanupFor === client && !it.isCompletedExceptionally) return it }
        val done = CompletableFuture<Void>()
        cleanupFor = client
        cleanup = done
        callbacks.execute {
            try {
                beforeDisconnect.apply(client).whenCompleteAsync({ _, failure ->
                    if (failure != null) done.completeExceptionally(failure)
                    else try { client.close(); done.complete(null) } catch (error: Throwable) { done.completeExceptionally(error) }
                }, callbacks)
            } catch (error: Throwable) { done.completeExceptionally(error) }
        }
        done.orTimeout(options.cleanupTimeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync({ _, error ->
            if (error != null) runCatching { client.abandon() }
        }, ForkJoinPool.commonPool())
        return done
    }

    private fun unavailable(result: CompletableFuture<MediaRuntime>, failure: MediaRuntimeException) {
        current = null
        change(ConnectionStatus.UNAVAILABLE, failure.detail)
        result.completeExceptionally(failure)
    }
    private fun change(status: ConnectionStatus, error: RuntimeError? = null, generation: Long = snapshot.generation) {
        snapshot = ConnectionSnapshot(status, generation, error)
        publisher.offer(snapshot) { _, _ -> false }
    }
    private fun enqueue(action: () -> Unit) { try { scheduler.execute(action) } catch (_: RejectedExecutionException) {} }
    private fun closedError() = MediaRuntimeException(RuntimeError("CLIENT_CLOSED", "Connection component is closed", pluginId))
    private fun cleanupError(error: Throwable): MediaRuntimeException {
        var cause = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null && seen.add(cause)) cause = cause.cause!!
        val reason = if (cause is TimeoutException) "Cleanup timed out after ${options.cleanupTimeoutMillis}ms"
            else "Cleanup failed (${cause.javaClass.simpleName})"
        return MediaRuntimeException(RuntimeError("CLEANUP_FAILED", "$reason; stop caller-owned processes and retry reconnect", pluginId, retryable = true)).apply { initCause(cause) }
    }

    override fun close() {
        val done = synchronized(lock) {
            if (stopped) return
            stopped = true; revision++
            scheduled?.cancel(false)
            current = null
            pending?.completeExceptionally(closedError())
            beginCleanup(cleanupClient)
        }
        try { done.get(options.cleanupTimeoutMillis, TimeUnit.MILLISECONDS) }
        catch (error: Throwable) {
            cleanupClient?.let { client -> CompletableFuture.runAsync { runCatching { client.abandon() } } }
            throw cleanupError(error)
        }
        finally {
            synchronized(lock) { change(ConnectionStatus.CLOSED); publisher.close() }
            scheduler.shutdownNow(); callbacks.shutdownNow()
        }
    }
}

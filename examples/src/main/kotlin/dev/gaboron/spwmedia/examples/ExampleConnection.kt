package dev.gaboron.spwmedia.examples

import dev.gaboron.spwmedia.sdk.*
import java.util.concurrent.*
import java.util.function.Function

/** Small presentation adapter; connection recovery and timing belong to the public SDK. */
class ExampleConnection(pluginId: String, cleanup: () -> Unit, changed: (ConnectionSnapshot) -> Unit) : AutoCloseable {
    private val connection = MediaRuntime.connectWhenAvailable(pluginId, ConnectionOptions(), Function {
        try { cleanup(); CompletableFuture.completedFuture(null) }
        catch (error: Throwable) { CompletableFuture.failedFuture(error) }
    })
    val current get() = connection.current
    val generation get() = connection.snapshot.generation
    init {
        connection.events.subscribe(object : Flow.Subscriber<ConnectionSnapshot> {
            override fun onSubscribe(subscription: Flow.Subscription) { subscription.request(Long.MAX_VALUE) }
            override fun onNext(item: ConnectionSnapshot) = changed(item)
            override fun onError(error: Throwable) { /* State remains queryable after subscriber failure. */ }
            override fun onComplete() {}
        })
        changed(connection.snapshot)
    }
    fun reconnect() { connection.reconnect() }
    override fun close() = connection.close()
}

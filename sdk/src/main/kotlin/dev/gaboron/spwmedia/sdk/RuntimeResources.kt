package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.RuntimeError
import java.util.concurrent.ConcurrentHashMap

/** Local ownership order: subscriptions, mappings, producer sessions, executable leases. */
internal class RuntimeResources {
    private val owned = ConcurrentHashMap<AutoCloseable, Int>()
    fun add(resource: AutoCloseable, order: Int) { owned[resource] = order }
    fun remove(resource: AutoCloseable) { owned.remove(resource) }
    fun checkSession(id: String) { owned.keys.filterIsInstance<RemoteResource>().find { it.resourceId == id }?.checkOpen() }
    fun close(abandon: Boolean = false) {
        var failure: Throwable? = null
        owned.entries.sortedBy { it.value }.forEach { (resource, _) ->
            try {
                if (abandon && resource is RemoteResource) resource.abandon() else resource.close()
                owned.remove(resource)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

/** Retains failed remote releases for retry; closing a handle never makes it usable again. */
internal class RemoteResource(val resourceId: String, private val client: MediaRuntime, order: Int) : AutoCloseable {
    private var closing = false
    private var closed = false
    init { client.own(this, order) }
    @Synchronized fun checkOpen() {
        if (closing) throw MediaRuntimeException(RuntimeError("SESSION_CLOSED", "Resource is closing or closed", sessionId = resourceId))
    }
    @Synchronized fun isClosed() = closing
    @Synchronized override fun close() {
        if (closed) return
        closing = true
        try { client.closeResource(resourceId) }
        catch (error: MediaRuntimeException) { if (!error.connectionGone()) throw error }
        closed = true
        client.forget(this)
    }
    @Synchronized fun abandon() { closing = true; closed = true; client.forget(this) }
}

internal fun MediaRuntimeException.connectionGone() = detail.code in setOf("RUNTIME_UNAVAILABLE", "CLIENT_CLOSED")

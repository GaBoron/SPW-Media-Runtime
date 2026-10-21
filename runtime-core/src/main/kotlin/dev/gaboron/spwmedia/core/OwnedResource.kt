package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.provider.ProviderFailure
import dev.gaboron.spwmedia.provider.ProviderSession
import kotlinx.serialization.json.JsonObject

/** Serializes commands with release; a failed release remains registered and retryable. */
internal class OwnedResource(
    val owner: String, val capability: String, private val delegate: ProviderSession,
    private val onDisconnect: () -> Unit = delegate::close
) {
    private var closing = false
    private var closed = false
    val session = object : ProviderSession {
        override fun command(method: String, arguments: JsonObject) = access { delegate.command(method, arguments) }
        override fun state() = access { delegate.state() }
        override fun failure() = access { delegate.failure() }
        override fun buffer() = access { delegate.buffer() }
        override fun close() = release(false)
    }
    private fun <T> access(action: () -> T): T {
        synchronized(this) { if (closing) throw ProviderFailure("SESSION_CLOSED", "Resource is closing or closed") }
        return action()
    }
    @Synchronized fun release(disconnected: Boolean) {
        if (closed) return
        closing = true
        if (disconnected) onDisconnect() else delegate.close()
        closed = true
    }
}

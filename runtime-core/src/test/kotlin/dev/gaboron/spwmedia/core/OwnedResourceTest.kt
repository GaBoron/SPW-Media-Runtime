package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OwnedResourceTest {
    @Test fun releaseFailureRetainsRetryAndRejectsCommands() {
        var calls = 0
        val resource = OwnedResource("owner", "test", object : ProviderSession {
            override fun command(method: String, arguments: JsonObject) = JsonNull
            override fun state() = JsonObject(emptyMap())
            override fun close() { if (++calls == 1) throw java.io.IOException("expected") }
        })
        assertFailsWith<java.io.IOException> { resource.release(false) }
        assertFailsWith<ProviderFailure> { resource.session.command("test", JsonObject(emptyMap())) }
        resource.release(false); resource.release(false)
        assertEquals(2, calls)
    }
    @Test fun unknownExternalProcessesKeepPinsOnDisconnect() {
        var pinsReleased = 0
        fun lease() = OwnedResource("owner", "tools", object : ProviderSession {
            override fun command(method: String, arguments: JsonObject) = JsonNull
            override fun state() = JsonObject(emptyMap())
            override fun close() { pinsReleased++ }
        }, onDisconnect = {})
        lease().release(true)
        assertEquals(0, pinsReleased)
        lease().release(false)
        assertEquals(1, pinsReleased)
    }
}

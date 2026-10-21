package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RuntimeEngineTest {
    private val storage = Files.createTempDirectory("spw-media-regression-")
    private val released = AtomicInteger()
    private val entered = CountDownLatch(1)
    private val provider = object : MediaProvider {
        override val id = "fake"
        override fun availability(assets: Assets) = Capability(id, available = true)
        override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement {
            if (operation == "wait") {
                context.onCancel { released.incrementAndGet() }
                entered.countDown()
                while (true) { context.checkCancelled(); Thread.sleep(5) }
            }
            return obj("id" to context.register(id, object : ProviderSession {
                override fun command(method: String, arguments: JsonObject) = JsonNull
                override fun state() = obj("state" to "OPEN")
                override fun close() { released.incrementAndGet() }
            }))
        }
    }
    private val assets = object : Assets {
        override val platform = "test"
        override fun find(name: String): Path? = null
        override fun version(name: String) = "test"
    }
    private val engine = RuntimeEngine(listOf(provider), assets, storage, emptyMap())
    @AfterTest fun close() { engine.close(); recycle(storage) }
    private fun connect(plugin: String) = engine.exchange(Request(operation = "connect", payload = obj("pluginId" to plugin)), plugin).value.jsonObject.text("token")
    private fun call(token: String, plugin: String, operation: String, payload: JsonObject = obj()) = engine.exchange(Request(token = token, operation = operation, capability = "fake", payload = payload), plugin)
    private fun submit(token: String, plugin: String, method: String, timeout: Long = 5000) = call(token, plugin, "submit", obj("method" to method, "arguments" to obj(), "timeoutMillis" to timeout)).value.jsonPrimitive.content
    private fun terminal(token: String, plugin: String, id: String): TaskSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            val state = Protocol.json.decodeFromJsonElement<TaskSnapshot>(call(token, plugin, "task", obj("id" to id)).value)
            if (state.state !in setOf("QUEUED", "RUNNING")) return state
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        error("Task did not reach a terminal state")
    }
    @Test fun disconnectClosesOnlyOwnedResources() {
        val a = connect("a"); val b = connect("b")
        val first = terminal(a, "a", submit(a, "a", "open")).result.jsonObject.text("id")
        val second = terminal(b, "b", submit(b, "b", "open")).result.jsonObject.text("id")
        assertEquals("RESOURCE_NOT_FOUND", call(b, "b", "closeResource", obj("id" to first)).error?.code)
        assertEquals("PERMISSION_DENIED", call(a, "b", "capabilities").error?.code)
        call(a, "a", "disconnect")
        assertEquals(1, released.get())
        assertNull(call(b, "b", "sessionState", obj("id" to second)).error)
        call(b, "b", "closeResource", obj("id" to second)); call(b, "b", "closeResource", obj("id" to second))
        assertEquals(2, released.get())
    }
    @Test fun cancellationIsTerminalAndScoped() {
        val token = connect("a"); val id = submit(token, "a", "wait")
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertTrue(call(token, "a", "cancel", obj("id" to id)).value.jsonPrimitive.boolean)
        assertEquals("CANCELLED", terminal(token, "a", id).state)
        assertFalse(call(token, "a", "cancel", obj("id" to id)).value.jsonPrimitive.boolean)
        assertEquals(1, released.get())
    }
    @Test fun deadlineCompletesWithoutClientPolling() {
        val token = connect("a"); val id = submit(token, "a", "wait", 100)
        assertEquals("TIMED_OUT", terminal(token, "a", id).state)
    }
    @Test fun rejectsProtocolMismatch() {
        assertEquals("PROTOCOL_INCOMPATIBLE", engine.exchange(Request(protocol = 99, operation = "connect"), "a").error?.code)
    }
}

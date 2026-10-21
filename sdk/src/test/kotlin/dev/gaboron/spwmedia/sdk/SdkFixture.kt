package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.core.*
import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*

internal class SdkFixture : AutoCloseable {
    val storage = Files.createTempDirectory("spw-sdk-lifecycle-")
    val releases = AtomicInteger()
    val failRelease = AtomicBoolean()
    var endpoint: RuntimeEndpoint? = null
    private val assets = object : Assets {
        override val platform = "test"
        override fun find(name: String): Path = storage.resolve(name)
        override fun version(name: String) = "test"
    }
    private val provider = object : MediaProvider {
        override val id = "test"
        override fun availability(assets: Assets) = Capability(id, available = true)
        override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement {
            if (operation == "wait") { Thread.sleep(200); return JsonNull }
            return obj("id" to context.register(id, object : ProviderSession {
                override fun command(method: String, arguments: JsonObject) = JsonNull
                override fun state() = obj("state" to "OPEN")
                override fun close() {
                    if (failRelease.compareAndSet(true, false)) throw java.io.IOException("release failed")
                    releases.incrementAndGet()
                }
            }))
        }
    }
    fun start() { check(endpoint == null); endpoint = RuntimeEndpoint(RuntimeEngine(listOf(provider), assets, storage, emptyMap())) { "lifecycle" } }
    fun stop() { endpoint?.close(); endpoint = null }
    fun connect() = MediaRuntime.connect("lifecycle")
    fun session(client: MediaRuntime): MediaSession {
        val id = client.invoke("test", "open").result.toCompletableFuture().get(3, TimeUnit.SECONDS).jsonObject.text("id")
        return MediaSession(id, "test", client)
    }
    override fun close() {
        stop()
        val helper = System.getProperty("spw.media.recycle.helper")
        val command = if (System.getProperty("os.name").startsWith("Windows") && helper != null) listOf(helper, storage.toString()) else listOf("gio", "trash", "--", storage.toString())
        if (!runCatching { ProcessBuilder(command).start().waitFor(15, TimeUnit.SECONDS) && !Files.exists(storage) }.getOrDefault(false))
            System.err.println("Recycle unavailable; retained test directory: $storage")
    }
}

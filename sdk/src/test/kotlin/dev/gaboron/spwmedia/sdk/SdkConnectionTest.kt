package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.core.*
import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SdkConnectionTest {
    @Test fun connectionCanBeRequestedAfterLateStartupAndRuntimeRestart() = temporaryStorage { storage ->
        val assets = object : Assets {
            override val platform = "test"
            override fun find(name: String): Path? = null
            override fun version(name: String) = "test"
        }
        fun endpoint() = RuntimeEndpoint(RuntimeEngine(emptyList(), assets, storage, emptyMap())) { "sdk-test" }
        assertEquals("RUNTIME_UNAVAILABLE", assertFailsWith<MediaRuntimeException> { MediaRuntime.connect("sdk-test") }.detail.code)
        val first = endpoint()
        val old = MediaRuntime.connect("sdk-test")
        first.close()
        assertEquals("RUNTIME_UNAVAILABLE", assertFailsWith<MediaRuntimeException> { old.capabilities() }.detail.code)
        endpoint().use {
            assertEquals("CLIENT_CLOSED", assertFailsWith<MediaRuntimeException> { old.capabilities() }.detail.code)
            runCatching { old.close() }
            MediaRuntime.connect("sdk-test").use { renewed -> assertTrue(renewed.capabilities().isEmpty()) }
        }
    }
    @Test fun clientCompletionAndShutdownUsePublicSdk() {
        val storage = Files.createTempDirectory("spw-media-sdk-regression-")
        val assets = object : Assets {
            override val platform = "test"
            override fun find(name: String): Path? = null
            override fun version(name: String) = "test"
        }
        val provider = object : MediaProvider {
            override val id = "fake"
            override fun availability(assets: Assets) = Capability(id, available = true)
            override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement {
                if (operation == "fail") throw ProviderFailure("FAKE_FAILURE", "expected failure")
                if (operation == "wait") while (true) { context.checkCancelled(); Thread.sleep(5) }
                return obj("answer" to 42)
            }
        }
        try {
            RuntimeEndpoint(RuntimeEngine(listOf(provider), assets, storage, emptyMap())) { "sdk-test" }.use {
                val client = MediaRuntime.connect("sdk-test")
                assertTrue(client.capabilities().single().available)
                assertEquals(42, client.invoke("fake", "echo").result.toCompletableFuture().get(3, TimeUnit.SECONDS).jsonObject["answer"]!!.jsonPrimitive.int)
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> { client.invoke("fake", "fail").result.toCompletableFuture().get(3, TimeUnit.SECONDS) }
                assertEquals("FAKE_FAILURE", (failure.cause as MediaRuntimeException).detail.code)
                val pending = client.invoke("fake", "wait").result.toCompletableFuture()
                client.close(); client.close()
                assertTrue(pending.isCompletedExceptionally)
                assertEquals("CLIENT_CLOSED", assertFailsWith<MediaRuntimeException> { client.capabilities() }.detail.code)
            }
        } finally {
            val helper = System.getProperty("spw.media.recycle.helper")
            val command = if (System.getProperty("os.name").startsWith("Windows") && helper != null) listOf(helper, storage.toString()) else listOf("gio", "trash", "--", storage.toString())
            val recycled = runCatching { ProcessBuilder(command).start().waitFor(15, TimeUnit.SECONDS) && !Files.exists(storage) }.getOrDefault(false)
            if (!recycled) System.err.println("Recycle unavailable; retained test directory: $storage")
        }
    }

    private fun temporaryStorage(action: (Path) -> Unit) {
        val storage = Files.createTempDirectory("spw-media-sdk-reconnect-")
        try { action(storage) } finally {
            val helper = System.getProperty("spw.media.recycle.helper")
            val command = if (System.getProperty("os.name").startsWith("Windows") && helper != null) listOf(helper, storage.toString()) else listOf("gio", "trash", "--", storage.toString())
            val recycled = runCatching { ProcessBuilder(command).start().waitFor(15, TimeUnit.SECONDS) && !Files.exists(storage) }.getOrDefault(false)
            if (!recycled) System.err.println("Recycle unavailable; retained test directory: $storage")
        }
    }
}

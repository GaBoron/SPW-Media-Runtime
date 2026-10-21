package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.core.*
import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SdkConnectionTest {
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
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) Desktop.getDesktop().moveToTrash(storage.toFile())
            else System.err.println("Recycle unavailable; retained test directory: $storage")
        }
    }
}

package dev.gaboron.spwmedia.metadata

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MetadataFailureTest {
    @Test fun unreadableMediaHasAnActionableStableError() {
        val root = Files.createTempDirectory("spw-metadata-regression-")
        val invocation = object : Invocation {
            override val owner = "metadata-test"
            override val operationId = "read"
            override val storage = root
            override val assets: Assets get() = error("Unused")
            override fun checkCancelled() {}
            override fun progress(value: Double) {}
            override fun onCancel(action: () -> Unit) = AutoCloseable {}
            override fun register(capability: String, session: ProviderSession): String = error("Unused")
            override fun session(id: String): ProviderSession = error("Unused")
            override fun process(arguments: List<String>, progress: Boolean, durationSeconds: Double?): ProcessResult = error("Unused")
            override fun worker(mode: String, arguments: JsonObject, lifetime: ResourceLifetime): ProviderSession = error("Unused")
            override fun buffer(capacity: Int, format: String, width: Int, height: Int, lifetime: ResourceLifetime): WritableBuffer = error("Unused")
            override fun <T> withFileLock(path: Path, block: () -> T) = block()
        }
        try {
            val input = Files.writeString(root.resolve("invalid.mp4"), "invalid media")
            val failure = assertFailsWith<ProviderFailure> { MetadataProvider().invoke(invocation, "read", obj("path" to input.toString())) }
            assertEquals("METADATA_READ_FAILED", failure.code)
            assertFalse(input.toString() in failure.message)
            assertFailsWith<NoSuchFileException> { MetadataProvider().invoke(invocation, "read", obj("path" to root.resolve("missing.flac").toString())) }
        } finally {
            val helper = System.getProperty("spw.media.recycle.helper")
            val command = if (System.getProperty("os.name").startsWith("Windows") && helper != null) listOf(helper, root.toString()) else listOf("gio", "trash", "--", root.toString())
            val recycled = runCatching { ProcessBuilder(command).start().waitFor(15, TimeUnit.SECONDS) && !Files.exists(root) }.getOrDefault(false)
            if (!recycled) System.err.println("Recycle unavailable; retained test directory: $root")
        }
    }
}

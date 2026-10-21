package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.provider.ProviderFailure
import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NativeAssetsTest {
    private val temporary = Files.createTempDirectory("spw-media-cache-test-")
    private val root = temporary.resolve("package")
    private val cache = temporary.resolve("cache")
    private val assets = NativeAssets(root, cache)
    private val source = root.resolve(assets.platform)
    init {
        Files.createDirectories(source)
        val files = mapOf("ffmpeg.exe" to "executable", "shared.dll" to "dependency", "mpv.dll" to "unused")
        files.forEach { (name, bytes) -> Files.writeString(source.resolve(name), bytes) }
        Files.writeString(source.resolve("assets.properties"), "ffmpeg.path=ffmpeg.exe\nffmpeg.version=test\nmpv.path=mpv.dll\n")
        val records = files.flatMap { (name, text) -> listOf("file.$name.size=${text.toByteArray().size}", "file.$name.digest=${MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }}") }
        Files.writeString(source.resolve("asset-manifest.properties"), "format=1\nidentity=${"a".repeat(64)}\nasset.ffmpeg.files=ffmpeg.exe,shared.dll\nasset.mpv.files=mpv.dll,shared.dll\n" + records.joinToString("\n"))
    }
    // Load after the fixture's package manifest has been written.
    private fun open() = NativeAssets(root, cache)
    @AfterTest fun cleanup() { assets.close(); recycle(temporary) }
    @Test fun queryDoesNotPrepareAndFirstUseCopiesOnlyDependencies() {
        open().use { active ->
            assertNotNull(active.locate("ffmpeg")); assertFalse(Files.exists(cache))
            val executable = active.find("ffmpeg")!!
            assertTrue(Files.exists(executable.resolveSibling("shared.dll")))
            assertFalse(Files.exists(executable.resolveSibling("mpv.dll")))
            val modified = Files.getLastModifiedTime(executable.resolveSibling("shared.dll"))
            assertNotNull(active.find("mpv"))
            assertEquals(modified, Files.getLastModifiedTime(executable.resolveSibling("shared.dll")))
        }
    }
    @Test fun concurrentPreparationReturnsOneCompleteGeneration() {
        open().use { active ->
            val executor = Executors.newFixedThreadPool(4)
            try {
                val paths = (1..12).map { executor.submit<Path> { active.find("ffmpeg")!! } }.map { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, paths.toSet().size)
                assertEquals("executable", Files.readString(paths.first()))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun corruptCacheIsNeverOverwritten() {
        val path = open().use { it.find("ffmpeg")!! }
        Files.writeString(path, "corrupted")
        open().use { active -> assertEquals("DEPENDENCY_MISSING", assertFailsWith<ProviderFailure> { active.find("ffmpeg") }.code) }
        assertEquals("corrupted", Files.readString(path))
    }
    @Test fun activeAndCurrentGenerationsCannotBeRecycled() {
        open().use { active ->
            val generation = active.find("ffmpeg")!!.parent
            var called = false
            assertEquals("active or unknown lease", inspectCacheGeneration(generation, "9.9.9", true) { called = true; true })
            assertEquals("current version", inspectCacheGeneration(generation, dev.gaboron.spwmedia.protocol.BuildInfo.VERSION, true) { called = true; true })
            assertFalse(called)
        }
    }
    @Test fun unknownCallerOwnedProcessesProtectAnInactiveGeneration() {
        val active = open()
        val generation = active.find("ffmpeg")!!.parent
        val tool = assertNotNull(active.externalTool())
        active.close()
        var recycled = false
        assertEquals("unknown caller-owned tool process", inspectCacheGeneration(generation, "9.9.9", true) { recycled = true; true })
        assertFalse(recycled)
        tool.close()
    }
    @Test fun runtimeShutdownKeepsUnreleasedExternalToolMarker() {
        val active = open()
        val engine = RuntimeEngine(emptyList(), active, temporary.resolve("runtime"), emptyMap())
        val token = engine.exchange(Request(operation = "connect", payload = obj("pluginId" to "caller")), "caller").value.jsonObject.text("token")
        val tool = engine.exchange(Request(token = token, operation = "acquireTool", payload = obj("name" to "ffmpeg")), "caller").value.jsonObject
        val generation = Path.of(tool.text("path")).parent
        engine.close()
        var recycled = false
        assertEquals("unknown caller-owned tool process", inspectCacheGeneration(generation, "9.9.9", true) { recycled = true; true })
        assertFalse(recycled)
    }
    @Test fun failedRecyclingLeavesGenerationEligibleForRetry() {
        val generation = open().use { it.find("ffmpeg")!!.parent }
        assertEquals("recycle pending; retained for retry", inspectCacheGeneration(generation, "9.9.9", true) { false })
        assertTrue(Files.isRegularFile(generation.resolve(".generation")))
        assertEquals("recycled", inspectCacheGeneration(generation, "9.9.9", true) { true })
    }
    @Test fun incompleteCopyHasNoPublishedEntryAndCanBeRetried() {
        Files.writeString(source.resolve("ffmpeg.exe"), "broken")
        open().use { active -> assertFails { active.find("ffmpeg") } }
        val generation = cache.resolve(assets.platform).resolve("a".repeat(64))
        assertFalse(Files.exists(generation.resolve("ffmpeg.exe")))
        Files.writeString(source.resolve("ffmpeg.exe"), "executable")
        open().use { assertNotNull(it.find("ffmpeg")) }
    }
    @Test fun traversalIsRejected() { assertFailsWith<IllegalArgumentException> { safeResolve(root, "../caller-data") } }
    @Test fun closeCannotBeFollowedByALatePreparation() {
        val active = open()
        active.close(); active.close()
        assertFailsWith<IllegalStateException> { active.find("ffmpeg") }
        assertFalse(Files.exists(cache))
    }
}

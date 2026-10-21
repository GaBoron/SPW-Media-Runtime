package dev.gaboron.spwmedia.core

import java.nio.file.Files
import kotlin.test.*

class BufferStorageTest {
    @Test fun orphanRecoverySkipsLiveOwnersAndRetainsFailedRecycles() {
        val root = Files.createTempDirectory("spw-media-buffer-test-")
        try {
            val orphan = Files.createDirectory(root.resolve("run-orphan"))
            Files.writeString(orphan.resolve(".owner"), "pid=9223372036854775807\nstarted=1970-01-01T00:00:00Z\n")
            Files.writeString(orphan.resolve(".active"), "")
            Files.writeString(orphan.resolve("old.buffer"), "old")
            val live = Files.createDirectory(root.resolve("run-live"))
            recordProcess(live.resolve(".owner"), ProcessHandle.current())
            Files.writeString(live.resolve(".active"), "")
            val attempts = mutableListOf<java.nio.file.Path>()
            val storage = BufferStorage(root) { attempts.add(it); false }
            assertContains(attempts, orphan); assertFalse(live in attempts)
            val buffer = Files.writeString(storage.directory.resolve("sample.buffer"), "data")
            storage.release(buffer)
            assertTrue(Files.exists(buffer)); assertTrue(Files.exists(storage.directory.resolve("sample.buffer.pending")))
            storage.close(); storage.close()
        } finally { recycle(root) }
    }
    @Test fun bufferCloseIsIdempotentAndStopsPublication() {
        val directory = Files.createTempDirectory("spw-media-map-test-")
        var releases = 0
        try {
            val mapping = MappedBuffer(directory, 16, "BGRA", 2, 2) { releases++ }
            mapping.publish(ByteArray(16), 2, 2)
            assertEquals(16, mapping.latest()?.size)
            mapping.close(); mapping.close()
            assertEquals(1, releases)
            assertFails { mapping.publish(ByteArray(16)) }
        } finally { recycle(directory) }
    }
}

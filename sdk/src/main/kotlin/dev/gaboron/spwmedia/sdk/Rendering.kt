package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.lang.foreign.Arena
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Frames copied from a shared channel. Sequence identifies freshness; bytes remain caller-owned. */
data class MediaBuffer(val sequence: Long, val bytes: ByteArray, val width: Int, val height: Int, val sampleRate: Int, val channels: Int)
/** Read-only memory mapping, independently closeable from its producer session. Always close before closing the producer. */
class SharedBuffer internal constructor(val descriptor: BufferDescriptor, private val client: MediaRuntime) : AutoCloseable {
    private val arena = Arena.ofShared()
    private val mapping = FileChannel.open(Path.of(descriptor.path), StandardOpenOption.READ).use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, descriptor.capacity.toLong() + 64, arena).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
    }
    init { client.own(this, 10) }
    /** Copies the latest stable block, or null when no frame exists/the producer is currently writing. No pointer escapes. */
    @Synchronized fun latest(): MediaBuffer? {
        repeat(3) {
            val before = mapping.getLong(8)
            java.lang.invoke.VarHandle.acquireFence()
            if (before == 0L || before and 1L != 0L) return@repeat
            val size = mapping.getInt(16)
            if (size !in 0..descriptor.capacity) return null
            val width = mapping.getInt(20); val height = mapping.getInt(24)
            val rate = mapping.getInt(28); val channels = mapping.getInt(32)
            val bytes = ByteArray(size)
            mapping.duplicate().position(64).get(bytes)
            java.lang.invoke.VarHandle.acquireFence()
            if (mapping.getLong(8) == before) return MediaBuffer(before, bytes, width, height, rate, channels)
        }
        return null
    }
    /** Deterministically unmaps this view. */
    @Synchronized override fun close() { if (arena.scope().isAlive) arena.close(); client.forget(this) }
}
/** Windows rendering targets, BGRA/RGBA conversion and caller-owned frame consumption. */
class Rendering internal constructor(private val client: MediaRuntime) {
    /** Allocate a bounded target; dimensions are pixels, format is BGRA or RGBA. */
    fun createTarget(width: Int, height: Int, format: String = "BGRA"): RuntimeTask<RenderTarget> = client.task("rendering", "create", obj("width" to width, "height" to height, "format" to format)) { RenderTarget(it.jsonObject.text("id"), client) }
    /** Convert a raw four-channel image file; format, dimensions and output are explicit. */
    fun convert(input: Path, output: Path, width: Int, height: Int, from: String, to: String): RuntimeTask<String> = client.task("rendering", "convert", obj("input" to input.toString(), "output" to output.toString(), "width" to width, "height" to height, "from" to from, "to" to to)) { it.jsonPrimitive.content }
}
/** Owned target. Frame publication reads a file to avoid putting pixel payloads into control messages. */
class RenderTarget internal constructor(id: String, client: MediaRuntime) : MediaSession(id, "rendering", client) {
    /** Load a complete raw frame from a file and publish it atomically to the target. */
    fun present(rawFrame: Path) = client.sessionTask(capability, id, "present", obj("path" to rawFrame.toString()))
    /** Maps this target. The caller owns and closes the returned view. */
    fun frames(): SharedBuffer = SharedBuffer(Protocol.json.decodeFromJsonElement(client.exchange("sessionBuffer", payload = obj("id" to id))), client)
}

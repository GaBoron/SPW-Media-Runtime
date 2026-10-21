package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.BufferDescriptor
import dev.gaboron.spwmedia.provider.WritableBuffer
import java.lang.foreign.Arena
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

/** Two-phase sequence counter; readers reject blocks changed while being copied. Slow readers skip old blocks. */
class MappedBuffer(directory: Path, capacity: Int, format: String, width: Int, height: Int, private val release: (Path) -> Unit = { recycle(it); Unit }) : WritableBuffer {
    private val arena = Arena.ofShared()
    private val path = directory.resolve("${UUID.randomUUID()}.buffer")
    override val descriptor = BufferDescriptor(path.toString(), capacity, format, width, height)
    private val mapping = run {
        require(capacity in 1..(256 * 1024 * 1024))
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE).use {
            it.map(FileChannel.MapMode.READ_WRITE, 0, capacity.toLong() + 64, arena).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
        }
    }
    init { mapping.putInt(0, 0x5350574d); mapping.putInt(4, 1) }
    @Synchronized override fun publish(bytes: ByteArray, width: Int, height: Int, sampleRate: Int, channels: Int) {
        require(bytes.size <= descriptor.capacity)
        val seq = mapping.getLong(8) + 1
        mapping.putLong(8, seq)
        java.lang.invoke.VarHandle.fullFence()
        mapping.putInt(16, bytes.size); mapping.putInt(20, width); mapping.putInt(24, height)
        mapping.putInt(28, sampleRate); mapping.putInt(32, channels)
        mapping.duplicate().position(64).put(bytes)
        java.lang.invoke.VarHandle.releaseFence()
        mapping.putLong(8, seq + 1)
    }
    @Synchronized override fun latest(): ByteArray? {
        repeat(3) {
            val sequence = mapping.getLong(8)
            if (sequence == 0L || sequence and 1L != 0L) return@repeat
            java.lang.invoke.VarHandle.acquireFence()
            val count = mapping.getInt(16)
            if (count !in 0..descriptor.capacity) return null
            val bytes = ByteArray(count); mapping.duplicate().position(64).get(bytes)
            java.lang.invoke.VarHandle.acquireFence()
            if (mapping.getLong(8) == sequence) return bytes
        }
        return null
    }
    @Synchronized override fun close() {
        if (!arena.scope().isAlive) return
        arena.close()
        release(path)
    }
}

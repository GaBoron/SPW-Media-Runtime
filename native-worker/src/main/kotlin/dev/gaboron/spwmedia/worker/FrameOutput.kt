package dev.gaboron.spwmedia.worker

import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference
import dev.gaboron.spwmedia.protocol.BufferDescriptor
import java.lang.foreign.Arena
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** libmpv software render API fallback: bounded resolution, CPU readback, explicit opaque BGRA frames. */
internal class FrameOutput(private val api: MpvApi, handle: Pointer, private val descriptor: BufferDescriptor, private val window: WindowOutput? = null, private val failed: () -> Unit) : AutoCloseable {
    private val renderer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "libmpv renderer").apply { isDaemon = true } }
    private var closed = false
    private val arena = Arena.ofShared()
    private val mapping = FileChannel.open(Path.of(descriptor.path), StandardOpenOption.READ, StandardOpenOption.WRITE).use {
        it.map(FileChannel.MapMode.READ_WRITE, 0, descriptor.capacity.toLong() + 64, arena).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
    }
    private val pixels = Memory(descriptor.capacity.toLong())
    private val context: Pointer
    fun resize(width: Int, height: Int) { window?.resize(width, height) }
    init {
        val name = Memory(3).apply { setString(0, "sw") }
        val args = parameters(1 to name)
        val target = PointerByReference()
        check(api.mpv_render_context_create(target, handle, args.pointer) >= 0) { "Cannot initialize software render context" }
        context = target.value
        renderer.scheduleWithFixedDelay({ runCatching { render() }.onFailure { failed() } }, 33, 33, TimeUnit.MILLISECONDS)
    }
    @Synchronized private fun render() {
        if (closed) return
        val size = Memory(8).apply { setInt(0, descriptor.width); setInt(4, descriptor.height) }
        val format = Memory(5).apply { setString(0, "bgr0") }
        val stride = Memory(Native.SIZE_T_SIZE.toLong()).apply { if (Native.SIZE_T_SIZE == 8) setLong(0, descriptor.width * 4L) else setInt(0, descriptor.width * 4) }
        val args = parameters(17 to size, 18 to format, 19 to stride, 20 to pixels)
        check(api.mpv_render_context_render(context, args.pointer) >= 0) { "Render failed" }
        val bytes = pixels.getByteArray(0, descriptor.capacity)
        for (i in 3 until bytes.size step 4) bytes[i] = -1
        val seq = mapping.getLong(8) + 1; mapping.putLong(8, seq); java.lang.invoke.VarHandle.fullFence()
        mapping.putInt(16, bytes.size); mapping.putInt(20, descriptor.width); mapping.putInt(24, descriptor.height)
        mapping.duplicate().position(64).put(bytes); java.lang.invoke.VarHandle.releaseFence(); mapping.putLong(8, seq + 1)
        window?.present(bytes, descriptor.width, descriptor.height)
    }
    @Synchronized override fun close() { if (closed) return; closed = true; renderer.shutdownNow(); api.mpv_render_context_free(context); pixels.close(); arena.close(); window?.close() }
}

private fun parameters(vararg fields: Pair<Int, Pointer>): RenderParameter {
    val first = RenderParameter()
    val array = first.toArray(fields.size + 1)
    fields.forEachIndexed { index, value -> (array[index] as RenderParameter).apply { type = value.first; data = value.second; write() } }
    (array.last() as RenderParameter).apply { type = 0; data = null; write() }
    return first
}

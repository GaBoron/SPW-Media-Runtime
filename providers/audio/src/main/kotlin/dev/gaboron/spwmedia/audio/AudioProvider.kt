package dev.gaboron.spwmedia.audio

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.*

/** Reference-counted shared native capture. Keys include all capture and analysis options. */
class AudioProvider : MediaProvider {
    override val id = "audio"
    private class Shared(val worker: ProviderSession, val plane: WritableBuffer, val fftSize: Int, var references: Int = 0) {
        var sequence: ByteArray? = null
        var features: AudioFeatures? = null
    }
    private val captures = mutableMapOf<String, Shared>()
    override fun availability(assets: Assets) = Capability(id, available = assets.platform == "windows-x64" && assets.locate("platform") != null, reason = if (assets.platform != "windows-x64") "UNSUPPORTED_PLATFORM" else "Windows platform helper required", features = listOf("loopback", "microphone", "PCM-f32le", "waveform", "FFT", "RMS", "peak", "dBFS"))
    override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement = when (operation) {
        "analyze" -> {
            val path = Path.of(arguments.text("path")); require(Files.size(path) <= 64 * 1024 * 1024)
            Protocol.json.encodeToJsonElement(analyze(Files.readAllBytes(path), arguments.number("sampleRate").toInt(), arguments.number("channels").toInt(), arguments.number("fftSize", 2048.0).toInt()))
        }
        "capture" -> synchronized(captures) {
            val options = Protocol.json.decodeFromJsonElement<CaptureOptions>(arguments)
            require(options.source in setOf("loopback", "microphone"))
            require(options.fftSize in 64..32768 && options.fftSize and (options.fftSize - 1) == 0)
            val key = Protocol.json.encodeToJsonElement(options).toString()
            val shared = captures.getOrPut(key) {
                val plane = context.buffer(4 * 32 * 32768, "PCM_F32LE", lifetime = ResourceLifetime.PROVIDER)
                try { Shared(context.worker("capture", JsonObject(arguments + ("buffer" to Protocol.json.encodeToJsonElement(plane.descriptor))), ResourceLifetime.PROVIDER), plane, options.fftSize) }
                catch (e: Throwable) { plane.close(); throw e }
            }
            shared.references++
            val session = object : ProviderSession {
                private var closed = false
                override fun command(method: String, arguments: JsonObject): JsonElement {
                    if (method != "features") throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown capture operation")
                    synchronized(shared) {
                        val bytes = shared.plane.latest() ?: throw ProviderFailure("CAPTURE_NOT_READY", "Audio has not arrived yet", true)
                        val state = shared.worker.state()
                        if (shared.sequence?.contentEquals(bytes) != true) {
                            shared.features = analyze(bytes, state.number("sampleRate", 48000.0).toInt(), state.number("channels", 2.0).toInt(), shared.fftSize)
                            shared.sequence = bytes
                        }
                        return Protocol.json.encodeToJsonElement(shared.features!!)
                    }
                }
                override fun state() = shared.worker.state()
                override fun failure() = shared.worker.failure()
                override fun buffer() = shared.plane.descriptor
                override fun close() = synchronized(captures) {
                    if (!closed) {
                        closed = true; shared.references--
                        if (shared.references == 0) { captures.remove(key); shared.worker.close(); shared.plane.close() }
                    }
                }
            }
            try { obj("id" to context.register(id, session)) } catch (e: Throwable) { session.close(); throw e }
        }
        else -> throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown analysis operation")
    }
    override fun close() = synchronized(captures) { captures.values.forEach { it.worker.close(); it.plane.close() }; captures.clear() }
}

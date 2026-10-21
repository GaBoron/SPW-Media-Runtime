package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.CompletionStage

/** PCM analysis and shared Windows capture; levels use linear amplitude and dBFS, not calibrated SPL or LUFS. */
class AudioAnalysis internal constructor(private val client: MediaRuntime) {
    /** Open a shared capture; identical options reuse its source and analysis. */
    fun capture(options: CaptureOptions = CaptureOptions()): RuntimeTask<AudioCapture> = client.task("audio", "capture", Protocol.json.encodeToJsonElement(options).jsonObject) { AudioCapture(it.jsonObject.text("id"), client) }
    /** Analyze a little-endian float32 interleaved PCM file in a background task. */
    fun analyze(pcm: Path, sampleRate: Int, channels: Int, fftSize: Int = 2048): RuntimeTask<AudioFeatures> = client.task("audio", "analyze", obj("path" to pcm.toString(), "sampleRate" to sampleRate, "channels" to channels, "fftSize" to fftSize)) { Protocol.json.decodeFromJsonElement(it) }
}
/** A subscription to a shared source. Closing it cannot close another subscriber's source. */
class AudioCapture internal constructor(id: String, client: MediaRuntime) : MediaSession(id, "audio", client) {
    /** Latest waveform, spectrum and levels. */
    fun features(): CompletionStage<AudioFeatures> = client.sessionTask(capability, id, "features").thenApply { Protocol.json.decodeFromJsonElement(it) }
    /** Raw float32 PCM samples use a bounded latest-block buffer; consumers may skip old blocks. */
    fun samples(): SharedBuffer = SharedBuffer(Protocol.json.decodeFromJsonElement(client.exchange("sessionBuffer", payload = obj("id" to id))), client)
}

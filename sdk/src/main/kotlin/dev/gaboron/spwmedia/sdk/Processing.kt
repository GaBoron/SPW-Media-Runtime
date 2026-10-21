package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Typed media operations; all paths are interpreted on the local runtime machine. */
class Processing internal constructor(private val client: MediaRuntime) {
    /** Inspect container, streams and tags without starting a player. */
    fun probe(input: Path): RuntimeTask<MediaInfo> = client.task("processing", "probe", obj("input" to input.toAbsolutePath().toString())) { Protocol.json.decodeFromJsonElement(it) }
    /** Transcode with explicit codecs and transforms; defaults retain FFmpeg's output format decisions. */
    fun transcode(options: TranscodeOptions, timeoutMillis: Long = 3_600_000): RuntimeTask<ProcessResult> = client.task("processing", "transcode", Protocol.json.encodeToJsonElement(options).jsonObject, timeoutMillis) { Protocol.json.decodeFromJsonElement(it) }
    /** Copy compressed streams into a new container without re-encoding. */
    fun remux(input: Path, output: Path, overwrite: Boolean = false) = transcode(TranscodeOptions(input.toString(), output.toString(), "copy", "copy", overwrite = overwrite))
    /** Extract one image at a timestamp in seconds. */
    fun extractFrame(input: Path, output: Path, atSeconds: Double = 0.0): RuntimeTask<ProcessResult> {
        require(atSeconds.isFinite() && atSeconds >= 0)
        return execute(listOf("-ss", atSeconds.toString(), "-i", input.toString(), "-frames:v", "1", output.toString()))
    }
    /** Extract an audio stream; codec copy retains its encoding. */
    fun extractAudio(input: Path, output: Path, codec: String = "copy") = execute(listOf("-i", input.toString(), "-vn", "-c:a", codec, output.toString()))
    /** Arbitrary FFmpeg arguments with runtime scheduling, bounded output, cancellation and structured errors. */
    fun execute(arguments: List<String>, timeoutMillis: Long = 3_600_000): RuntimeTask<ProcessResult> = client.task("processing", "execute", obj("arguments" to arguments), timeoutMillis) { Protocol.json.decodeFromJsonElement(it) }
    /** Arbitrary managed FFprobe arguments. */
    fun executeProbe(arguments: List<String>, timeoutMillis: Long = 120_000): RuntimeTask<ProcessResult> = client.task("processing", "executeProbe", obj("arguments" to arguments), timeoutMillis) { Protocol.json.decodeFromJsonElement(it) }
}

/** Direct executable discovery. Self-started processes do not create RuntimeTask progress/events. */
class Tools internal constructor(private val client: MediaRuntime) {
    /** Acquire ffmpeg or ffprobe; keep the returned lease open until your process terminates. */
    fun acquire(name: String): ToolLease = ToolLease(Protocol.json.decodeFromJsonElement(client.exchange("acquireTool", payload = obj("name" to name))), client)
}
/** Pins an immutable component version. Updating the runtime does not replace an active lease's path. */
class ToolLease internal constructor(val descriptor: ToolDescriptor, private val client: MediaRuntime) : AutoCloseable {
    private val resource = RemoteResource(descriptor.leaseId, client, 30)
    /** Absolute executable path; no PATH lookup or copying is necessary. */
    val path: Path get() { resource.checkOpen(); return Path.of(descriptor.path) }
    /** Call only after caller-owned processes using this executable have exited. */
    override fun close() = resource.close()
}

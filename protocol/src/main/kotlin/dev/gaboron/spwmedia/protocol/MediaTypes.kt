package dev.gaboron.spwmedia.protocol

import kotlinx.serialization.Serializable

/** FFprobe stream information; time values are seconds, dimensions are pixels. */
@Serializable data class MediaStream(val index: Int, val type: String, val codec: String = "", val width: Int = 0, val height: Int = 0, val sampleRate: Int = 0, val channels: Int = 0, val tags: Map<String, String> = emptyMap())
/** Container information with structured streams and unmodified metadata values. */
@Serializable data class MediaInfo(val path: String, val format: String, val durationSeconds: Double? = null, val sizeBytes: Long? = null, val streams: List<MediaStream> = emptyList(), val tags: Map<String, String> = emptyMap())
/** Structured filter options; advanced FFmpeg filters remain available through [TranscodeOptions.extraArguments]. */
@Serializable data class VideoTransform(val width: Int? = null, val height: Int? = null, val crop: String? = null, val filters: List<String> = emptyList())
/** Argument arrays are passed directly to FFmpeg, never through a shell. Overwrite is opt-in. */
@Serializable data class TranscodeOptions(val input: String, val output: String, val videoCodec: String? = null, val audioCodec: String? = null, val format: String? = null, val video: VideoTransform? = null, val extraArguments: List<String> = emptyList(), val overwrite: Boolean = false)
/** Managed command result. Output is bounded to the last 1 MiB per stream. */
@Serializable data class ProcessResult(val exitCode: Int, val stdout: String = "", val stderr: String = "")
/** libmpv options. A nonzero parentWindow is a borrowed Windows HWND. Zero frame dimensions create a software display window; positive dimensions select shared frames. */
@Serializable data class PlayerOptions(val hardwareDecoding: String = "auto-safe", val parentWindow: Long = 0, val options: Map<String, String> = emptyMap(), val frameWidth: Int = 0, val frameHeight: Int = 0)
/** Snapshot of libmpv state, in seconds and percent volume. */
@Serializable data class PlayerState(val paused: Boolean = true, val positionSeconds: Double = 0.0, val durationSeconds: Double = 0.0, val buffering: Boolean = false, val speed: Double = 1.0, val volume: Double = 100.0, val media: String = "")
/** Browser profile names are local to the owning plugin; parentWindow is a borrowed Windows HWND. */
@Serializable data class WebOptions(val url: String = "about:blank", val profile: String = "default", val parentWindow: Long = 0, val width: Int = 960, val height: Int = 640, val allowedOrigins: List<String> = emptyList())
/** Cookie access is scoped to this browser's profile. Empty domain uses WebView2's own validation. */
@Serializable data class WebCookie(val name: String, val value: String, val domain: String, val path: String = "/", val secure: Boolean = false, val httpOnly: Boolean = false, val expiresUnixSeconds: Double = -1.0)
/** Supported tag fields are advertised by the metadata provider. Cover bytes use an explicit file transfer. */
@Serializable data class MediaTags(val fields: Map<String, List<String>> = emptyMap(), val artworkCount: Int = 0)
/** Audio capture source: loopback or microphone; deviceId empty selects the default endpoint. */
@Serializable data class CaptureOptions(val source: String = "loopback", val deviceId: String = "", val fftSize: Int = 2048)
/** RMS and peak are linear amplitudes; levelDbfs is RMS relative to full scale. Spectrum contains linear magnitudes. */
@Serializable data class AudioFeatures(val sampleRate: Int, val channels: Int, val rms: Double, val peak: Double, val levelDbfs: Double, val waveform: List<Float>, val spectrum: List<Double>)

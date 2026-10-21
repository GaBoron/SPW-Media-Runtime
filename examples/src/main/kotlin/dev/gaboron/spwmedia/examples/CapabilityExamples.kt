package dev.gaboron.spwmedia.examples

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.sdk.*
import java.nio.file.Path
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture

/** User-action entry points. The caller retains returned sessions and closes them on view/plugin disposal. */
class CapabilityExamples(private val media: MediaRuntime) {
    /** Windows/Linux independent player; loading failure closes the newly allocated player. */
    fun play(input: Path): CompletionStage<Player> = media.playback.open().result.thenCompose { player ->
        player.load(input.toString()).handle { _, error ->
            if (error != null) { player.close(); throw java.util.concurrent.CompletionException(error) }
            player
        }
    }

    /** Windows browser. This profile retains login across view and host restarts. */
    fun browser(url: String, allowedOrigin: String): CompletionStage<Browser> =
        media.web.open(WebOptions(url = url, profile = "example", allowedOrigins = listOf(allowedOrigin))).result

    /** A capture subscription is independent even when the underlying loopback source is shared. */
    fun capture(): CompletionStage<AudioCapture> = media.audio.capture(CaptureOptions(source = "loopback", fftSize = 2048)).result

    /** File-level metadata is for information not already available through the caller's host Library access. */
    fun tags(path: Path): CompletionStage<MediaTags> = media.metadata.read(path).result

    /** Explicit user action writes only the requested title field. */
    fun renameTitle(path: Path, title: String): CompletionStage<MediaTags> = media.metadata.write(path, mapOf("TITLE" to listOf(title))).result

    /** Export a new FLAC, then tag that output. Failure skips the tag write; keep the individual RuntimeTasks when exposing per-step cancellation. */
    fun exportTaggedAudio(input: Path, output: Path, title: String): CompletionStage<MediaTags> =
        media.processing.extractAudio(input, output, "flac").result.thenCompose {
            media.metadata.write(output, mapOf("TITLE" to listOf(title))).result
        }

    /** Prefer the host's Library cover API for library items. Explicit export creates a new file only when artwork exists. */
    fun exportFirstArtwork(input: Path, output: Path): CompletionStage<Path?> = media.metadata.read(input).result.thenCompose { tags ->
        if (tags.artworkCount == 0) CompletableFuture.completedFuture<Path?>(null)
        else media.metadata.extractArtwork(input, output).result.thenApply { Path.of(it) }
    }

    /** Explicit edit of caller-selected audio; null removes its artwork. Do not call from plugin startup. */
    fun replaceArtwork(input: Path, image: Path?): CompletionStage<MediaTags> = media.metadata.setArtwork(input, image).result

    /** Analyze a float32 little-endian PCM file without opening a recording device. */
    fun analyzePcm(input: Path, sampleRate: Int, channels: Int): CompletionStage<AudioFeatures> =
        media.audio.analyze(input, sampleRate, channels, fftSize = 2048).result

    /** Convert the byte order of a caller-owned raw frame into a new file, with explicit dimensions. */
    fun rgbaFrame(input: Path, output: Path, width: Int, height: Int): CompletionStage<String> =
        media.rendering.convert(input, output, width, height, "BGRA", "RGBA").result

    /** Raw BGRA input remains caller-owned. Dispose its MediaSurface before closing frames/target. */
    fun image(rawFrame: Path, width: Int, height: Int): CompletionStage<RenderTarget> =
        media.rendering.createTarget(width, height).result.thenCompose { target ->
            target.present(rawFrame).handle { _, error ->
                if (error != null) { target.close(); throw java.util.concurrent.CompletionException(error) }
                target
            }
        }
}

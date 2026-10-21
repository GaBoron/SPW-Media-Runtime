package dev.gaboron.spwmedia.examples

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.sdk.*
import java.nio.file.Path
import java.util.concurrent.CompletionStage

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

    /** Raw BGRA input remains caller-owned. Dispose its MediaSurface before closing frames/target. */
    fun image(rawFrame: Path, width: Int, height: Int): CompletionStage<RenderTarget> =
        media.rendering.createTarget(width, height).result.thenCompose { target ->
            target.present(rawFrame).handle { _, error ->
                if (error != null) { target.close(); throw java.util.concurrent.CompletionException(error) }
                target
            }
        }
}

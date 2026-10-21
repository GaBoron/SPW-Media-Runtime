package dev.gaboron.spwmedia.playback

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*

/** Independent libmpv workers; a crashed player cannot crash the SPW JVM. */
class PlaybackProvider : MediaProvider {
    override val id = "playback"
    override fun availability(assets: Assets) = Capability(id, available = assets.locate("mpv") != null && assets.locate("worker") != null, reason = if (assets.platform == "unsupported") "UNSUPPORTED_PLATFORM" else "libmpv and JVM worker bootstrap required", features = listOf("player", "commands", "hardware-decoding", "state", "frames"))
    override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement {
        if (operation != "open") throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown playback operation")
        val requested = Protocol.json.decodeFromJsonElement<PlayerOptions>(arguments)
        require((requested.frameWidth == 0 && requested.frameHeight == 0) || (requested.frameWidth in 1..3840 && requested.frameHeight in 1..2160))
        val displayWindow = requested.frameWidth == 0
        if (context.assets.platform == "linux-x64" && requested.parentWindow != 0L) throw ProviderFailure("OPERATION_UNSUPPORTED", "Linux native window embedding is unavailable; consume player frames instead")
        val options = if (displayWindow) requested.copy(frameWidth = 960, frameHeight = 540) else requested
        require((options.frameWidth == 0 && options.frameHeight == 0) || (options.frameWidth in 1..3840 && options.frameHeight in 1..2160))
        val buffer = if (options.frameWidth > 0) context.buffer(Math.multiplyExact(Math.multiplyExact(options.frameWidth, options.frameHeight), 4), "BGRA", options.frameWidth, options.frameHeight) else null
        try {
            val worker = context.worker("playback", JsonObject(Protocol.json.encodeToJsonElement(options).jsonObject + mapOf("displayWindow" to JsonPrimitive(displayWindow), "buffer" to (buffer?.let { Protocol.json.encodeToJsonElement(it.descriptor) } ?: JsonNull))))
            val session = object : ProviderSession by worker {
                override fun close() { try { worker.close() } finally { buffer?.close() } }
            }
            return obj("id" to context.register(id, session))
        } catch (e: Throwable) { buffer?.close(); throw e }
    }
}

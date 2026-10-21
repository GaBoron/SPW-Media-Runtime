package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.util.concurrent.CompletionStage

/** Each open call owns an independent libmpv process and playback state. */
class Playback internal constructor(private val client: MediaRuntime) {
    /** Options are applied before libmpv initialization; hardware support is determined by the backend/device. */
    fun open(options: PlayerOptions = PlayerOptions()): RuntimeTask<Player> = client.task("playback", "open", Protocol.json.encodeToJsonElement(options).jsonObject) { Player(it.jsonObject.text("id"), client) }
}
/** Independent player. Playback completion/failure is visible in [snapshot] and [events]. */
class Player internal constructor(id: String, client: MediaRuntime) : MediaSession(id, "playback", client) {
    /** Loads a path/URI, preserving the player. Completion acknowledges the command; observe state/events for media loading or playback failure. */
    fun load(media: String): CompletionStage<JsonElement> = command("loadfile", listOf(media, "replace"))
    /** Resume current media. */
    fun play() = setOption("pause", "no")
    /** Pause current media. */
    fun pause() = setOption("pause", "yes")
    /** Stop without destroying this player. */
    fun stop() = command("stop")
    /** Absolute timestamp in seconds. */
    fun seek(seconds: Double): CompletionStage<JsonElement> { require(seconds.isFinite() && seconds >= 0); return command("seek", listOf(seconds.toString(), "absolute+exact")) }
    /** Playback speed multiplier, greater than zero. */
    fun setSpeed(speed: Double): CompletionStage<JsonElement> { require(speed.isFinite() && speed > 0); return setOption("speed", speed.toString()) }
    /** Volume in percent, 0..100. */
    fun setVolume(percent: Double): CompletionStage<JsonElement> { require(percent.isFinite() && percent in 0.0..100.0); return setOption("volume", percent.toString()) }
    /** Resize the standalone/borrowed player window in pixels; shared frame dimensions stay fixed. */
    fun resize(width: Int, height: Int): CompletionStage<JsonElement> { require(width > 0 && height > 0); return client.sessionTask(capability, id, "resize", obj("width" to width, "height" to height)) }
    /** libmpv property access, with backend-specific option semantics. */
    fun setOption(name: String, value: String) = client.sessionTask(capability, id, "set", obj("name" to name, "value" to value))
    /** Advanced command using an argument array, never command-string parsing. */
    fun command(name: String, arguments: List<String> = emptyList()) = client.sessionTask(capability, id, "command", obj("name" to name, "arguments" to arguments))
    /** Typed playback values. */
    fun state(): PlayerState = Protocol.json.decodeFromJsonElement(snapshot().values)
    /** Frames are available only when frameWidth/frameHeight were requested at creation. */
    fun frames(): SharedBuffer = SharedBuffer(Protocol.json.decodeFromJsonElement(client.exchange("sessionBuffer", payload = obj("id" to id))), client)
}

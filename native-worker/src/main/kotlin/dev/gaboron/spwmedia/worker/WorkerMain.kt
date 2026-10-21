package dev.gaboron.spwmedia.worker

import com.sun.jna.*
import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Child JVM entry point; libmpv is loaded only here, never in SPW's JVM. */
fun main(arguments: Array<String>) {
    val api = Native.load(arguments.single(), MpvApi::class.java)
    var handle: Pointer? = null
    var frames: FrameOutput? = null
    val backendFailure = java.util.concurrent.atomic.AtomicReference("")
    val lock = Any()
    val output = System.out.bufferedWriter(Charsets.UTF_8)
    fun emit(value: JsonObject) = synchronized(output) { output.append(value.toString()).append('\n'); output.flush() }
    fun checkCode(code: Int) { require(code >= 0) { "libmpv error $code" } }
    fun property(name: String): String {
        val pointer = api.mpv_get_property_string(handle!!, name) ?: return ""
        return try { pointer.getString(0, "UTF-8") } finally { api.mpv_free(pointer) }
    }
    val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "libmpv state").apply { isDaemon = true } }
    try {
        ticker.scheduleWithFixedDelay({ synchronized(lock) {
            if (handle != null) runCatching {
                val event = api.mpv_wait_event(handle!!, 0.0)
                val eventError = event.getInt(4)
                if (eventError < 0) backendFailure.set("PLAYBACK_FAILED")
                if (event.getInt(0) == 7) event.getPointer(16)?.let { if (it.getInt(4) < 0) backendFailure.set("PLAYBACK_FAILED") }
                val failure = backendFailure.get()
                val values = obj("state" to if (failure.isNotEmpty()) "FAILED" else "OPEN", "errorCode" to failure, "paused" to (property("pause") == "yes"), "positionSeconds" to (property("time-pos").toDoubleOrNull() ?: 0.0), "durationSeconds" to (property("duration").toDoubleOrNull() ?: 0.0), "buffering" to (property("paused-for-cache") == "yes"), "speed" to (property("speed").toDoubleOrNull() ?: 1.0), "volume" to (property("volume").toDoubleOrNull() ?: 100.0), "media" to property("path"))
                emit(obj("event" to "state", "values" to values))
            }.onFailure { backendFailure.set("PLAYBACK_FAILED") }
        } }, 100, 100, TimeUnit.MILLISECONDS)
        System.`in`.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
            val request = Protocol.json.parseToJsonElement(line).jsonObject
            val id = request["id"]!!
            try {
                val result = synchronized(lock) {
                    val args = request["arguments"]!!.jsonObject
                    when (request.text("method")) {
                        "open" -> {
                            val options = Protocol.json.decodeFromJsonElement<PlayerOptions>(args)
                            require(handle == null)
                            handle = api.mpv_create() ?: error("Cannot create libmpv")
                            val defaults = mutableMapOf("config" to "no", "load-scripts" to "no", "terminal" to "no", "idle" to "yes", "hwdec" to options.hardwareDecoding)
                            if (options.frameWidth > 0) { defaults["vo"] = "libmpv"; defaults["profile"] = "sw-fast" }
                            (defaults + options.options).forEach { (key, value) -> checkCode(api.mpv_set_option_string(handle!!, key, value)) }
                            checkCode(api.mpv_initialize(handle!!))
                            args["buffer"]?.takeUnless { it is JsonNull }?.let {
                                val descriptor = Protocol.json.decodeFromJsonElement<BufferDescriptor>(it)
                                val window = if (args["displayWindow"]?.jsonPrimitive?.booleanOrNull == true) WindowOutput(descriptor.width, descriptor.height, options.parentWindow) else null
                                frames = FrameOutput(api, handle!!, descriptor, window) { backendFailure.set("RENDER_FAILED") }
                            }
                            JsonNull
                        }
                        "set" -> { checkCode(api.mpv_set_property_string(handle!!, args.text("name"), args.text("value"))); JsonNull }
                        "resize" -> { frames?.resize(args.number("width").toInt(), args.number("height").toInt()); JsonNull }
                        "command" -> {
                            val values = listOf(args.text("name")) + (args["arguments"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
                            checkCode(api.mpv_command(handle!!, StringArray(values.toTypedArray(), "UTF-8")))
                            backendFailure.set("")
                            JsonNull
                        }
                        else -> error("Unsupported player command")
                    }
                }
                emit(obj("id" to id, "result" to result))
            } catch (_: Throwable) { emit(obj("id" to id, "code" to "PLAYBACK_FAILED", "error" to "libmpv rejected the operation")) }
        } }
    } finally {
        ticker.shutdownNow()
        synchronized(lock) { frames?.close(); handle?.let { api.mpv_terminate_destroy(it) }; handle = null }
    }
}

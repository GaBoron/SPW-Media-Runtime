package dev.gaboron.spwmedia.processing

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/** FFmpeg CLI backend; no FFmpeg library is loaded into the host JVM. */
class ProcessingProvider : MediaProvider {
    override val id = "processing"
    override fun availability(assets: Assets) = Capability(id, available = assets.locate("ffmpeg") != null && assets.locate("ffprobe") != null, reason = if (assets.platform == "unsupported") "UNSUPPORTED_PLATFORM" else "FFmpeg/FFprobe required", features = listOf("probe", "transcode", "remux", "extract", "filters", "execute", "executeProbe"))
    override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement = when (operation) {
        "probe" -> {
            val input = arguments.text("input")
            val result = context.process(listOf(context.assets.find("ffprobe")!!.toString(), "-v", "error", "-show_format", "-show_streams", "-of", "json", input))
            Protocol.json.encodeToJsonElement(parseProbe(input, Protocol.json.parseToJsonElement(result.stdout).jsonObject))
        }
        "transcode" -> {
            val options = Protocol.json.decodeFromJsonElement<TranscodeOptions>(arguments)
            val args = mutableListOf(if (options.overwrite) "-y" else "-n", "-i", options.input)
            options.videoCodec?.let { args.addAll(listOf("-c:v", it)) }
            options.audioCodec?.let { args.addAll(listOf("-c:a", it)) }
            options.format?.let { args.addAll(listOf("-f", it)) }
            options.video?.let { video ->
                val filters = mutableListOf<String>()
                if (video.width != null || video.height != null) filters.add("scale=${video.width ?: -2}:${video.height ?: -2}")
                video.crop?.let { filters.add("crop=$it") }; filters.addAll(video.filters)
                if (filters.isNotEmpty()) args.addAll(listOf("-vf", filters.joinToString(",")))
            }
            args.addAll(options.extraArguments); args.add(options.output)
            context.withFileLock(Path.of(options.output)) {
                val duration = runCatching {
                    val probe = context.process(listOf(context.assets.find("ffprobe")!!.toString(), "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", options.input))
                    probe.stdout.trim().toDoubleOrNull()
                }.getOrNull()
                execute(context, "ffmpeg", args, duration)
            }
        }
        "execute" -> execute(context, "ffmpeg", arguments.strings("arguments"))
        "executeProbe" -> execute(context, "ffprobe", arguments.strings("arguments"))
        else -> throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown processing operation")
    }
    private fun execute(context: Invocation, tool: String, arguments: List<String>, duration: Double? = null): JsonElement {
        val prefix = if (tool == "ffmpeg") listOf("-nostdin", "-progress", "pipe:1", "-nostats") else emptyList()
        return Protocol.json.encodeToJsonElement(context.process(listOf(context.assets.find(tool)!!.toString()) + prefix + arguments, tool == "ffmpeg", duration))
    }
}

private fun parseProbe(path: String, value: JsonObject): MediaInfo {
    fun tags(obj: JsonObject?): Map<String, String> = obj?.get("tags")?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
    val format = value["format"]?.jsonObject ?: obj()
    val streams = value["streams"]?.jsonArray?.map { element ->
        val stream = element.jsonObject
        MediaStream(stream.number("index").toInt(), stream.text("codec_type", "unknown"), stream.text("codec_name", ""), stream.number("width").toInt(), stream.number("height").toInt(), stream.number("sample_rate").toInt(), stream.number("channels").toInt(), tags(stream))
    } ?: emptyList()
    return MediaInfo(path, format.text("format_name", ""), format["duration"]?.jsonPrimitive?.doubleOrNull, format["size"]?.jsonPrimitive?.longOrNull, streams, tags(format))
}

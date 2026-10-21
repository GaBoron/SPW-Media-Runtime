package dev.gaboron.spwmedia.rendering

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.*

/** Bounded image targets and explicit BGRA/RGBA conversion; Skia objects stay in the consumer's UI layer. */
class RenderingProvider : MediaProvider {
    override val id = "rendering"
    override fun availability(assets: Assets) = Capability(id, available = assets.platform == "windows-x64", reason = if (assets.platform != "windows-x64") "UNSUPPORTED_PLATFORM" else "", features = listOf("BGRA", "RGBA", "targets", "conversion"))
    override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement {
        val width = arguments.number("width").toInt(); val height = arguments.number("height").toInt()
        require(width in 1..3840 && height in 1..2160)
        val bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4)
        return when (operation) {
            "create" -> {
                val format = arguments.text("format", "BGRA"); require(format in setOf("BGRA", "RGBA"))
                val buffer = context.buffer(bytes, format, width, height)
                val session = object : ProviderSession {
                    override fun command(method: String, arguments: JsonObject): JsonElement {
                        if (method != "present") throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown target operation")
                        val path = Path.of(arguments.text("path")); require(Files.size(path) == bytes.toLong())
                        buffer.publish(Files.readAllBytes(path), width, height)
                        return JsonNull
                    }
                    override fun state() = obj("state" to "OPEN", "width" to width, "height" to height, "format" to format)
                    override fun buffer() = buffer.descriptor
                    override fun close() = buffer.close()
                }
                obj("id" to context.register(id, session))
            }
            "convert" -> {
                val from = arguments.text("from"); val to = arguments.text("to")
                require(from in setOf("BGRA", "RGBA") && to in setOf("BGRA", "RGBA"))
                val input = Path.of(arguments.text("input")); require(Files.size(input) == bytes.toLong())
                val data = Files.readAllBytes(input)
                if (from != to) for (i in data.indices step 4) { val temp = data[i]; data[i] = data[i + 2]; data[i + 2] = temp }
                val output = Path.of(arguments.text("output"))
                Files.write(output, data, StandardOpenOption.CREATE_NEW)
                JsonPrimitive(output.toAbsolutePath().toString())
            }
            else -> throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown rendering operation")
        }
    }
}

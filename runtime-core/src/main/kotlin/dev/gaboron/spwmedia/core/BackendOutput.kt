package dev.gaboron.spwmedia.core

import java.io.Reader
import dev.gaboron.spwmedia.protocol.Protocol
import kotlinx.serialization.json.*

/** Bounded even for a backend that emits an arbitrarily long line without a newline. */
internal class BackendOutput(private val limit: Int = 1_048_576) {
    private val tail = StringBuilder()
    @Synchronized fun append(value: String) { tail.append(value); if (tail.length > limit) tail.delete(0, tail.length - limit) }
    @Synchronized fun text() = tail.toString()
    fun capture(reader: Reader, progress: (String) -> Unit = {}): String = reader.use {
        val block = CharArray(4096)
        val line = StringBuilder()
        while (true) {
            val count = it.read(block)
            if (count < 0) break
            append(String(block, 0, count))
            for (index in 0 until count) {
                val character = block[index]
                if (character == '\n') { progress(line.toString().trimEnd('\r')); line.setLength(0) }
                else if (line.length < 4096) line.append(character)
            }
        }
        if (line.isNotEmpty()) progress(line.toString())
        text()
    }
}

internal object BackendRedaction {
    private val urls = Regex("(?i)https?://[^\\s\"'<>]+")
    private val sensitiveLine = Regex("(?im)^.*(?:authorization|cookie|password|passwd|token|secret|api[-_]key)[^\\r\\n]*$")
    fun tail(output: String, arguments: List<String> = emptyList()): String {
        var safe = sensitiveLine.replace(output, "[redacted sensitive line]")
        safe = urls.replace(safe, "[redacted URL]")
        val secrets = mutableSetOf<String>()
        fun collect(value: JsonElement, key: String = "") {
            when (value) {
                is JsonObject -> value.forEach { (name, item) -> collect(item, name) }
                is JsonArray -> value.forEach { collect(it, key) }
                is JsonPrimitive -> if (value.isString) {
                    val text = value.content
                    if (Regex("(?i).*?(?:authorization|cookie|password|passwd|token|secret|api[-_]key).*?").matches(key)) secrets.add(text)
                    runCatching { java.net.URI(text) }.getOrNull()?.let { uri ->
                        uri.userInfo?.split(':')?.forEach(secrets::add)
                        uri.rawQuery?.split('&')?.forEach { pair -> pair.substringAfter('=', "").takeIf { it.isNotBlank() }?.let { secrets.add(java.net.URLDecoder.decode(it, Charsets.UTF_8)) } }
                    }
                }
            }
        }
        arguments.forEachIndexed { index, argument ->
            runCatching { Protocol.json.parseToJsonElement(argument) }.getOrNull()?.let { collect(it) }
            if (argument.matches(Regex("(?i)--?(?:password|passwd|token|secret|api[-_]key)"))) arguments.getOrNull(index + 1)?.let(secrets::add)
            Regex("(?i)(?:password|passwd|token|secret|api[-_]key)[=:]([^\\s&;]+)").findAll(argument).forEach { secrets.add(it.groupValues[1]) }
            runCatching { java.net.URI(argument) }.getOrNull()?.let { uri ->
                uri.userInfo?.split(':')?.forEach(secrets::add)
                uri.rawQuery?.split('&')?.forEach { pair -> pair.substringAfter('=', "").takeIf { it.isNotBlank() }?.let { secrets.add(java.net.URLDecoder.decode(it, Charsets.UTF_8)) } }
            }
        }
        secrets.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { safe = safe.replace(it, "[redacted]") }
        val bytes = safe.toByteArray(Charsets.UTF_8)
        return bytes.copyOfRange((bytes.size - 8192).coerceAtLeast(0), bytes.size).toString(Charsets.UTF_8).trimStart('\uFFFD')
    }
}

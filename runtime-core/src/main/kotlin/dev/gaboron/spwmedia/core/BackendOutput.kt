package dev.gaboron.spwmedia.core

import java.io.Reader
import dev.gaboron.spwmedia.protocol.Protocol
import kotlinx.serialization.json.*

/** Bounded even for a backend that emits an arbitrarily long line without a newline. */
internal class BackendOutput(private val limit: Int = 1_048_576) {
    private val tail = ByteArray(limit.also { require(it > 0) })
    private var length = 0
    private var next = 0
    private var highSurrogate: Char? = null
    @Synchronized fun append(value: String) {
        if (value.isEmpty()) return
        var text = (highSurrogate?.toString() ?: "") + value
        highSurrogate = text.last().takeIf { it.isHighSurrogate() }
        if (highSurrogate != null) text = text.dropLast(1)
        write(text.toByteArray(Charsets.UTF_8))
    }
    private fun write(bytes: ByteArray) {
        if (bytes.size >= limit) {
            bytes.copyInto(tail, 0, bytes.size - limit)
            next = 0; length = limit
        } else {
            val first = minOf(bytes.size, limit - next)
            bytes.copyInto(tail, next, 0, first)
            bytes.copyInto(tail, 0, first)
            next = (next + bytes.size) % limit; length = minOf(limit, length + bytes.size)
        }
    }
    @Synchronized fun text(): String {
        val bytes = ByteArray(length)
        val start = (next - length + limit) % limit
        val first = minOf(length, limit - start)
        tail.copyInto(bytes, 0, start, start + first)
        tail.copyInto(bytes, first, 0, length - first)
        return bytes.toString(Charsets.UTF_8).trimStart('\uFFFD')
    }
    @Synchronized private fun finish() { highSurrogate?.let { write(it.toString().toByteArray(Charsets.UTF_8)) }; highSurrogate = null }
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
        finish()
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
        fun credential(text: String) {
            if (text.isBlank()) return
            secrets.add(text)
            Regex("(?i)^(?:Bearer|Basic)\\s+(.+)$").matchEntire(text.trim())?.let { secrets.add(it.groupValues[1]) }
        }
        fun collect(value: JsonElement, key: String = "") {
            when (value) {
                is JsonObject -> value.forEach { (name, item) -> collect(item, name) }
                is JsonArray -> value.forEach { collect(it, key) }
                is JsonPrimitive -> if (value.isString) {
                    val text = value.content
                    if (Regex("(?i).*?(?:authorization|cookie|password|passwd|token|secret|api[-_]key).*?").matches(key)) {
                        credential(text)
                        if (key.contains("cookie", true)) text.split(';').forEach { pair -> pair.substringAfter('=', "").takeIf { it.isNotBlank() }?.let(::credential) }
                    }
                    runCatching { java.net.URI(text) }.getOrNull()?.let { uri ->
                        uri.userInfo?.split(':')?.forEach(secrets::add)
                        uri.rawQuery?.split('&')?.forEach { pair -> pair.substringAfter('=', "").takeIf { it.isNotBlank() }?.let { secrets.add(java.net.URLDecoder.decode(it, Charsets.UTF_8)) } }
                    }
                }
            }
        }
        arguments.forEachIndexed { index, argument ->
            runCatching { Protocol.json.parseToJsonElement(argument) }.getOrNull()?.let { collect(it) }
            Regex("(?im)(?:authorization|proxy-authorization):\\s*(?:Bearer|Basic)\\s+([^\\s]+)").findAll(argument).forEach { credential(it.groupValues[1]) }
            Regex("(?im)(?:^|\\n)cookie:\\s*([^\\r\\n]+)").findAll(argument).forEach { header -> header.groupValues[1].split(';').forEach { pair -> pair.substringAfter('=', "").takeIf { it.isNotBlank() }?.let(::credential) } }
            if (argument.matches(Regex("(?i)--?(?:password|passwd|token|secret|api[-_]key)"))) arguments.getOrNull(index + 1)?.let(secrets::add)
            Regex("(?i)(?:password|passwd|token|secret|api[-_]key)[=:]([^\\s&;]+)").findAll(argument).forEach { secrets.add(it.groupValues[1]) }
            runCatching { java.net.URI(argument) }.getOrNull()?.let { uri ->
                uri.userInfo?.split(':')?.forEach(secrets::add)
                uri.rawQuery?.split('&')?.forEach { pair -> pair.substringAfter('=', "").takeIf { it.isNotBlank() }?.let { secrets.add(java.net.URLDecoder.decode(it, Charsets.UTF_8)) } }
            }
        }
        val known = secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        if (known.size > 128 || known.sumOf { it.length } > 65536) return "[redacted backend output]"
        if (known.isNotEmpty()) safe = Regex(known.joinToString("|") { Regex.escape(it) }).replace(safe, "[redacted]")
        val bytes = safe.toByteArray(Charsets.UTF_8)
        return bytes.copyOfRange((bytes.size - 8192).coerceAtLeast(0), bytes.size).toString(Charsets.UTF_8).trimStart('\uFFFD')
    }
}

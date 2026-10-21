package dev.gaboron.spwmedia.protocol

import kotlinx.serialization.json.*

/** Build a protocol object using JSON-compatible scalar/list/object values. */
fun obj(vararg entries: Pair<String, Any?>): JsonObject = JsonObject(entries.associate { it.first to valueOf(it.second) })
/** Converts only protocol values; arbitrary implementation objects are never accepted. */
fun valueOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map(::valueOf))
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to valueOf(it.value) })
    else -> error("Not a protocol value: ${value.javaClass.name}")
}
/** Required string field. Missing/invalid input is reported as INVALID_ARGUMENT by the runtime. */
fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
/** Optional string field with an explicit default. */
fun JsonObject.text(name: String, default: String): String = get(name)?.jsonPrimitive?.contentOrNull ?: default
/** Optional numeric field with an explicit default. */
fun JsonObject.number(name: String, default: Double = 0.0): Double = get(name)?.jsonPrimitive?.doubleOrNull ?: default

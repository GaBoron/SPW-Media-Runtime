package dev.gaboron.spwmedia.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Stable protocol identifiers. Unknown capabilities and added object fields are ignored by older clients. */
object Protocol {
    const val VERSION = 1
    const val RUNTIME_VERSION = BuildInfo.VERSION
    const val OBJECT_NAME = "dev.gaboron.spwmedia:type=Runtime,protocol=1"
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

/** Request IDs are unique per invocation; retrying an operation creates a new operation. */
@Serializable data class Request(val protocol: Int = 1, val token: String = "", val operation: String, val capability: String = "", val payload: JsonObject = JsonObject(emptyMap()))
/** A transport response carries either a JSON value or a stable error, never an implementation exception. */
@Serializable data class Response(val value: JsonElement = JsonNull, val error: RuntimeError? = null)
/** Error codes are open strings; callers must retain a fallback for future codes. */
@Serializable data class RuntimeError(val code: String, val message: String, val pluginId: String = "", val operationId: String = "", val sessionId: String = "", val retryable: Boolean = false, val backend: BackendFailure? = null)
/** Owner-only failure context. stderrTail is redacted and limited to 8 KiB of UTF-8; diagnostics omit it. */
@Serializable data class BackendFailure(val backend: String, val exitCode: Int? = null, val stderrTail: String = "")
/** Redacted failure record. Contains no media path, URL, script, cookie or backend output. Time is epoch milliseconds. */
@Serializable data class DiagnosticEvent(val timestampMillis: Long, val error: RuntimeError)
/** Availability describes the current platform/dependencies, not a successful functional acceptance run. */
@Serializable data class Capability(val id: String, val version: String = "0.1.0", val available: Boolean, val reason: String = "", val features: List<String> = emptyList())
/** Immutable status; states are QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT. */
@Serializable data class TaskSnapshot(val id: String, val state: String, val progress: Double? = null, val result: JsonElement = JsonNull, val error: RuntimeError? = null)
/** Immutable session event. Payload and state depend on the advertised capability. */
@Serializable data class SessionSnapshot(val id: String, val capability: String, val state: String, val values: JsonObject = JsonObject(emptyMap()), val error: RuntimeError? = null)
/** Paths remain pinned while their lease and owning connection remain open. Never infer another asset's path. */
@Serializable data class ToolDescriptor(val leaseId: String, val name: String, val version: String, val platform: String, val path: String)
/** Descriptor for a bounded latest-frame mapping. Samples are copied before delivery to user callbacks. */
@Serializable data class BufferDescriptor(val path: String, val capacity: Int, val format: String, val width: Int = 0, val height: Int = 0, val channels: Int = 0, val sampleRate: Int = 0)

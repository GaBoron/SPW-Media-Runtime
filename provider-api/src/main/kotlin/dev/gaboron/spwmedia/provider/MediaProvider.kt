package dev.gaboron.spwmedia.provider

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Internal extensibility contract. Providers own media logic; core owns scheduling and cross-plugin routing. */
interface MediaProvider : AutoCloseable {
    val id: String
    fun availability(assets: Assets): Capability
    fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement
    override fun close() {}
}
/** Discover only declared immutable packaged assets. Paths never come from PATH. */
interface Assets {
    val platform: String
    fun find(name: String): Path?
    /** Side-effect-free package lookup. The returned path is for availability checks, not a leased tool. */
    fun locate(name: String): Path? = find(name)
    fun version(name: String): String
}
/** A provider invocation scoped to a single owner and operation, including cancellation. */
interface Invocation {
    val owner: String
    val operationId: String
    val assets: Assets
    val storage: Path
    fun checkCancelled()
    fun progress(value: Double)
    fun onCancel(action: () -> Unit): AutoCloseable
    fun register(capability: String, session: ProviderSession): String
    fun session(id: String): ProviderSession
    fun process(arguments: List<String>, progress: Boolean = false, durationSeconds: Double? = null): ProcessResult
    fun worker(mode: String, arguments: JsonObject, lifetime: ResourceLifetime = ResourceLifetime.INVOCATION): ProviderSession
    fun buffer(capacity: Int, format: String, width: Int = 0, height: Int = 0, lifetime: ResourceLifetime = ResourceLifetime.INVOCATION): WritableBuffer
    fun <T> withFileLock(path: Path, block: () -> T): T
}
/** Shared provider resources use PROVIDER lifetime and must be reference-counted by their provider. */
enum class ResourceLifetime { INVOCATION, PROVIDER }
/** State is queried independently of command execution; close is required to be idempotent. */
interface ProviderSession : AutoCloseable {
    fun command(method: String, arguments: JsonObject): JsonElement
    fun state(): JsonObject
    fun buffer(): BufferDescriptor? = null
    fun failure(): RuntimeError? = null
}
/** Latest-frame writable plane. Descriptor is a locator with an explicit owned lifetime. */
interface WritableBuffer : AutoCloseable {
    val descriptor: BufferDescriptor
    fun publish(bytes: ByteArray, width: Int = 0, height: Int = 0, sampleRate: Int = 0, channels: Int = 0)
    fun latest(): ByteArray?
}
/** Recoverable provider error mapped to the public stable error envelope. */
class ProviderFailure(val code: String, override val message: String, val retryable: Boolean = false, val backend: BackendFailure? = null) : RuntimeException(message)

/** Read a string array from validated protocol input. */
fun JsonObject.strings(name: String): List<String> = get(name)?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

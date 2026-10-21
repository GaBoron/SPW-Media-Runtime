package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.management.ObjectName

/** A structured runtime failure. [detail] is safe to show; raw backend output is available only to its owner. */
class MediaRuntimeException(val detail: RuntimeError) : RuntimeException("${detail.code}: ${detail.message}")

/**
 * Plugin-scoped SDK connection. Keep one per plugin and close it from the plugin's stop callback.
 * Methods enqueue work; completion and event delivery occur on SDK background threads, never the UI thread.
 * The SDK contains no native backend. Multiple isolated copies may connect to the same JVM-local runtime.
 */
class MediaRuntime private constructor(private val pluginId: String) : AutoCloseable {
    private val server = ManagementFactory.getPlatformMBeanServer()
    private val name = ObjectName(Protocol.OBJECT_NAME)
    private val closed = AtomicBoolean()
    private val executor = Executors.newScheduledThreadPool(2) { r -> Thread(r, "SPW Media SDK").apply { isDaemon = true } }
    private var token = ""
    private val active = ConcurrentHashMap.newKeySet<RuntimeTask<*>>()
    private val localResources = ConcurrentHashMap.newKeySet<AutoCloseable>()
    /** Common and advanced managed FFmpeg operations. */
    val processing = Processing(this)
    /** Independent libmpv player sessions. */
    val playback = Playback(this)
    /** Windows WebView2 sessions and persistent profiles. */
    val web = WebEngine(this)
    /** Tags and embedded artwork. */
    val metadata = Metadata(this)
    /** Windows capture and PCM analysis. */
    val audio = AudioAnalysis(this)
    /** Bounded frame targets and pixel conversion. */
    val rendering = Rendering(this)
    /** Leased access to shared executables, with caller-owned process execution. */
    val tools = Tools(this)

    init {
        try { token = exchange("connect", payload = obj("pluginId" to pluginId, "sdkVersion" to Protocol.RUNTIME_VERSION)).jsonObject.text("token") }
        catch (e: Throwable) { executor.shutdownNow(); throw e }
    }

    /** Query current availability without initializing a heavy backend. */
    fun capabilities(): List<Capability> = Protocol.json.decodeFromJsonElement(exchange("capabilities"))

    /** Recent redacted failures for this authenticated plugin, bounded by the runtime's 256-record history. Since 0.1.0. */
    fun diagnostics(): List<DiagnosticEvent> = Protocol.json.decodeFromJsonElement(exchange("diagnostics"))

    /** Extension entry point for advertised provider operations absent from this SDK version. Uses the same managed task contract. */
    fun invoke(capability: String, operation: String, arguments: JsonObject = obj(), timeoutMillis: Long = 120_000): RuntimeTask<JsonElement> = task(capability, operation, arguments, timeoutMillis) { it }

    internal fun exchange(operation: String, capability: String = "", payload: JsonObject = obj()): JsonElement {
        if (closed.get()) throw MediaRuntimeException(RuntimeError("CLIENT_CLOSED", "Connection is closed", pluginId))
        try {
            if (!server.isRegistered(name)) throw MediaRuntimeException(RuntimeError("RUNTIME_UNAVAILABLE", "Enable SPW Media Runtime before connecting", pluginId, retryable = true))
            val raw = server.invoke(name, "exchange", arrayOf(Protocol.json.encodeToString(Request(token = token, operation = operation, capability = capability, payload = payload))), arrayOf("java.lang.String")) as String
            val response = Protocol.json.decodeFromString<Response>(raw)
            response.error?.let { throw MediaRuntimeException(it) }
            return response.value
        } catch (e: MediaRuntimeException) { throw e }
        catch (e: Exception) { throw MediaRuntimeException(RuntimeError("TRANSPORT_FAILED", "Runtime connection failed: ${e.javaClass.simpleName}", pluginId, retryable = true)) }
    }

    @Synchronized internal fun <T> task(capability: String, operation: String, payload: JsonObject = obj(), timeoutMillis: Long = 120_000, decode: (JsonElement) -> T): RuntimeTask<T> {
        require(timeoutMillis > 0)
        val id = exchange("submit", capability, obj("method" to operation, "arguments" to payload, "timeoutMillis" to timeoutMillis)).jsonPrimitive.content
        return RuntimeTask(id, this, executor, decode) { active.remove(it) }.also { active.add(it); it.start() }
    }

    internal fun sessionTask(capability: String, id: String, method: String, arguments: JsonObject = obj()): CompletionStage<JsonElement> =
        task(capability, "session", obj("id" to id, "method" to method, "arguments" to arguments), decode = { it }).result

    internal fun watch(session: MediaSession): Flow.Publisher<SessionSnapshot> = SessionEvents(session, executor, this)
    @Synchronized internal fun own(resource: AutoCloseable) {
        if (closed.get()) { resource.close(); throw MediaRuntimeException(RuntimeError("CLIENT_CLOSED", "Connection is closed", pluginId)) }
        localResources.add(resource)
    }
    internal fun forget(resource: AutoCloseable) { localResources.remove(resource) }
    @Synchronized internal fun closeResource(id: String) { if (!closed.get()) exchange("closeResource", payload = obj("id" to id)) }

    /** Cancels owned tasks and closes owned sessions/leases. Does not remove persistent browser data. Idempotent. */
    @Synchronized override fun close() {
        if (closed.get()) return
        localResources.toList().forEach { runCatching { it.close() } }
        localResources.clear()
        try { exchange("disconnect") } finally {
            closed.set(true)
            active.toList().forEach { it.disconnected() }
            executor.shutdown()
        }
    }

    companion object {
        /** Connect using PluginContext.pluginId, on a background thread; throws RUNTIME_UNAVAILABLE if absent. */
        @JvmStatic fun connect(pluginId: String): MediaRuntime {
            require(pluginId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid plugin ID" }
            return MediaRuntime(pluginId)
        }
        /** Lightweight presence query; still negotiate capabilities after connecting. */
        @JvmStatic fun isInstalled(): Boolean = ManagementFactory.getPlatformMBeanServer().isRegistered(ObjectName(Protocol.OBJECT_NAME))
    }
}

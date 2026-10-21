package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/** Line-delimited correlated control messages; pixel/PCM data is confined to mapped files. */
internal class WorkerSession(command: List<String>, private val initial: JsonObject, onSpawn: (Process) -> Unit) : ProviderSession {
    private val process = ProcessBuilder(command).apply {
        if (System.getProperty("os.name").startsWith("Linux")) environment()["LD_LIBRARY_PATH"] = (Path.of(command.last()).parent?.toString() ?: "") + ":" + environment().getOrDefault("LD_LIBRARY_PATH", "")
    }.start().also(onSpawn)
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val sequence = AtomicLong()
    @Volatile private var current = obj("state" to "STARTING")
    @Volatile private var error: RuntimeError? = null
    @Volatile private var closed = false
    private val readers = Executors.newVirtualThreadPerTaskExecutor()
    init {
        readers.submit {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                    val message = Protocol.json.parseToJsonElement(line).jsonObject
                    val id = message["id"]?.jsonPrimitive?.longOrNull
                    if (id != null) {
                        val target = pending.remove(id)
                        val failure = message["error"]?.jsonPrimitive?.contentOrNull
                        if (failure == null) target?.complete(message["result"] ?: JsonNull)
                        else target?.completeExceptionally(ProviderFailure(message.text("code", "BACKEND_FAILED"), failure))
                    } else if (message["event"]?.jsonPrimitive?.content == "state") current = message["values"]!!.jsonObject
                } }
            } catch (_: Exception) { if (!closed) error = RuntimeError("BACKEND_FAILED", "Backend control channel failed") }
            finally {
                if (!closed) error = error ?: RuntimeError("BACKEND_EXITED", "Backend exited unexpectedly", retryable = true)
                pending.values.forEach { it.completeExceptionally(ProviderFailure("BACKEND_EXITED", "Backend exited")) }
                pending.clear()
            }
        }
        readers.submit { process.errorStream.bufferedReader().useLines { it.forEach { /* Never log backend messages containing credentials. */ } } }
        try { command("open", initial) } catch (e: Exception) { close(); throw e }
    }
    override fun command(method: String, arguments: JsonObject): JsonElement {
        if (closed) throw ProviderFailure("SESSION_CLOSED", "Session is closed")
        val id = sequence.incrementAndGet()
        val future = CompletableFuture<JsonElement>(); pending[id] = future
        try {
            synchronized(writer) { writer.append(obj("id" to id, "method" to method, "arguments" to arguments).toString()).append('\n'); writer.flush() }
            return future.get(60, TimeUnit.SECONDS)
        } catch (e: ExecutionException) { throw (e.cause ?: e) }
        catch (e: TimeoutException) { close(); throw ProviderFailure("BACKEND_TIMEOUT", "Backend did not reply", true) }
        finally { pending.remove(id) }
    }
    override fun state(): JsonObject = current
    override fun failure() = error ?: current["errorCode"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { RuntimeError(it, "Backend session failed", retryable = true) }
    override fun buffer(): BufferDescriptor? = initial["buffer"]?.takeUnless { it is JsonNull }?.let { Protocol.json.decodeFromJsonElement(it) }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { writer.close() }
        ManagedProcess.terminate(process)
        readers.shutdownNow()
    }
}

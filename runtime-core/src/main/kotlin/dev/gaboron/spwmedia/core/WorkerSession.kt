package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/** Line-delimited correlated control messages; pixel/PCM data is confined to mapped files. */
internal class WorkerSession(command: List<String>, private val initial: JsonObject, private val backend: String = "native", onSpawn: (Process) -> Unit) : ProviderSession {
    private val process = try { ProcessBuilder(command).apply {
        if (System.getProperty("os.name").startsWith("Linux")) environment()["LD_LIBRARY_PATH"] = (Path.of(command.last()).parent?.toString() ?: "") + ":" + environment().getOrDefault("LD_LIBRARY_PATH", "")
    }.start() } catch (error: java.io.IOException) {
        throw ProviderFailure("BACKEND_START_FAILED", "Backend process could not be started", backend = BackendFailure(backend, stderrTail = BackendRedaction.tail(error.message.orEmpty(), command)))
    }.also { spawned -> try { onSpawn(spawned) } catch (error: Throwable) { ManagedProcess.terminate(spawned); throw error } }
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val sequence = AtomicLong()
    @Volatile private var current = obj("state" to "STARTING")
    @Volatile private var error: RuntimeError? = null
    @Volatile private var closed = false
    private val closing = java.util.concurrent.atomic.AtomicBoolean()
    private val stderr = BackendOutput()
    private val readers = Executors.newVirtualThreadPerTaskExecutor()
    init {
        readers.submit {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> while (true) {
                    val line = readControlLine(reader) ?: break
                    val message = Protocol.json.parseToJsonElement(line).jsonObject
                    val id = message["id"]?.jsonPrimitive?.longOrNull
                    if (id != null) {
                        val target = pending.remove(id)
                        val failure = message["error"]?.jsonPrimitive?.contentOrNull
                        if (failure == null) target?.complete(message["result"] ?: JsonNull)
                        else target?.completeExceptionally(ProviderFailure(message.text("code", "BACKEND_FAILED"), "Backend rejected the operation", backend = detail()))
                    } else if (message["event"]?.jsonPrimitive?.content == "state") current = message["values"]!!.jsonObject
                } }
            } catch (_: Exception) { if (!closed) error = RuntimeError("BACKEND_FAILED", "Backend control channel failed") }
            finally {
                if (!closed) {
                    error = error ?: RuntimeError("BACKEND_EXITED", "Backend exited unexpectedly", retryable = true)
                    current = obj("state" to "FAILED")
                    val failure = error!!
                    pending.values.forEach { it.completeExceptionally(ProviderFailure(failure.code, failure.message, failure.retryable, detail())) }
                    pending.clear()
                    ManagedProcess.terminate(process)
                }
            }
        }
        readers.submit { runCatching { stderr.capture(process.errorStream.reader(Charsets.UTF_8)) } }
        try { command("open", initial) } catch (e: Exception) { close(); throw e }
    }
    override fun command(method: String, arguments: JsonObject): JsonElement {
        if (closed) throw ProviderFailure("SESSION_CLOSED", "Session is closed")
        failure()?.let { throw ProviderFailure(it.code, it.message, it.retryable, it.backend) }
        val id = sequence.incrementAndGet()
        val future = CompletableFuture<JsonElement>(); pending[id] = future
        try {
            synchronized(writer) { if (closed) throw ProviderFailure("SESSION_CLOSED", "Session is closed"); writer.append(obj("id" to id, "method" to method, "arguments" to arguments).toString()).append('\n'); writer.flush() }
            return future.get(60, TimeUnit.SECONDS)
        } catch (e: ExecutionException) { throw (e.cause ?: e) }
        catch (e: TimeoutException) { close(); throw ProviderFailure("BACKEND_TIMEOUT", "Backend did not reply", true) }
        finally { pending.remove(id) }
    }
    override fun state(): JsonObject = current
    private fun detail() = BackendFailure(backend, if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull(), BackendRedaction.tail(stderr.text(), listOf(initial.toString())))
    override fun failure() = (error ?: current["errorCode"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { RuntimeError(it, "Backend session failed", retryable = true) })?.copy(backend = detail())
    override fun buffer(): BufferDescriptor? = initial["buffer"]?.takeUnless { it is JsonNull }?.let { Protocol.json.decodeFromJsonElement(it) }
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        closed = true
        current = obj("state" to "CLOSED")
        pending.values.forEach { it.completeExceptionally(ProviderFailure("SESSION_CLOSED", "Session was closed")) }; pending.clear()
        ManagedProcess.terminate(process)
        runCatching { writer.close() }
        runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
        readers.shutdownNow()
    }
}

internal fun readControlLine(reader: java.io.Reader): String? {
    val line = StringBuilder()
    while (true) {
        val value = reader.read()
        if (value < 0) return if (line.isEmpty()) null else line.toString()
        if (value == 10) return line.toString().trimEnd('\r')
        if (line.length >= 1_048_576) throw IllegalArgumentException("Backend control message exceeds 1 MiB")
        line.append(value.toChar())
    }
}

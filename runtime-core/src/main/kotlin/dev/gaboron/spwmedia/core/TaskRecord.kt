package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.ProviderFailure
import kotlinx.serialization.json.*
import java.util.concurrent.*

internal class TaskRecord(val id: String, val owner: String, val pluginId: String) {
    @Volatile var snapshot = TaskSnapshot(id, "QUEUED")
    var future: Future<*>? = null
    var timeout: ScheduledFuture<*>? = null
    private val cancellation = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var stopped = false
    fun check() { if (stopped || Thread.currentThread().isInterrupted) throw CancellationException() }
    fun onCancel(action: () -> Unit): AutoCloseable {
        val invoked = java.util.concurrent.atomic.AtomicBoolean()
        val once = { if (invoked.compareAndSet(false, true)) action() }
        cancellation.add(once)
        if (stopped) { cancellation.remove(once); runCatching(once) }
        return AutoCloseable { cancellation.remove(once) }
    }
    @Synchronized fun running(): Boolean { if (snapshot.state != "QUEUED") return false; snapshot = snapshot.copy(state = "RUNNING"); return true }
    @Synchronized fun progress(value: Double) { if (snapshot.state == "RUNNING") snapshot = snapshot.copy(progress = value.coerceIn(0.0, 1.0)) }
    @Synchronized fun success(result: JsonElement) { if (!stopped) { stopped = true; snapshot = snapshot.copy(state = "SUCCEEDED", progress = 1.0, result = result); timeout?.cancel(false); cancellation.clear() } }
    fun fail(error: Throwable) {
        synchronized(this) {
            if (stopped) return
            stopped = true
            snapshot = snapshot.copy(state = "FAILED", error = publicError(error, pluginId, id))
            timeout?.cancel(false)
        }
        cancellation.forEach { runCatching(it) }
        cancellation.clear()
    }
    fun cancel(timedOut: Boolean = false): Boolean {
        synchronized(this) {
            if (snapshot.state !in setOf("QUEUED", "RUNNING")) return false
            stopped = true
            val code = if (timedOut) "TIMED_OUT" else "CANCELLED"
            snapshot = snapshot.copy(state = code, error = RuntimeError(code, "Task ${code.lowercase()}", pluginId, id, retryable = timedOut))
        }
        cancellation.forEach { runCatching(it) }
        cancellation.clear()
        future?.cancel(true); timeout?.cancel(false)
        return true
    }
}

internal fun publicError(error: Throwable, plugin: String = "", operation: String = "", session: String = ""): RuntimeError {
    val root = if (error is ExecutionException) error.cause ?: error else error
    val code = when (root) {
        is ProviderFailure -> root.code
        is CancellationException, is InterruptedException -> "CANCELLED"
        is IllegalArgumentException, is NoSuchElementException -> "INVALID_ARGUMENT"
        is java.nio.file.NoSuchFileException -> "FILE_NOT_FOUND"
        is java.nio.file.AccessDeniedException, is SecurityException -> "PERMISSION_DENIED"
        is java.io.IOException -> "IO_FAILED"
        else -> "INTERNAL_ERROR"
    }
    val message = if (root is ProviderFailure) root.message else when (code) {
        "INVALID_ARGUMENT" -> "Invalid or missing request argument"
        "FILE_NOT_FOUND" -> "Requested file was not found"
        "PERMISSION_DENIED" -> "Access to the requested resource was denied"
        else -> "Operation failed ($code)"
    }
    return RuntimeError(code, message, plugin, operation, session, (root as? ProviderFailure)?.retryable ?: false, (root as? ProviderFailure)?.backend)
}

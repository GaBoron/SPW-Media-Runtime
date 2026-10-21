package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.ProcessResult
import dev.gaboron.spwmedia.protocol.BackendFailure
import dev.gaboron.spwmedia.provider.ProviderFailure
import java.nio.file.Path
import java.util.concurrent.*

/** No shell; both pipes are consumed concurrently and process-tree termination is scoped to the managed child. */
internal object ManagedProcess {
    fun run(arguments: List<String>, cancelled: () -> Unit, onCancel: (() -> Unit) -> AutoCloseable, progress: (Double) -> Unit, durationSeconds: Double? = null, onSpawn: (Process) -> Unit = {}): ProcessResult {
        require(arguments.isNotEmpty() && arguments.none { '\u0000' in it })
        cancelled()
        val process = ProcessBuilder(arguments).apply {
            val parent = Path.of(arguments.first()).toAbsolutePath().parent
            if (System.getProperty("os.name").startsWith("Linux")) environment()["LD_LIBRARY_PATH"] = parent.resolve("../lib").normalize().toString() + ":" + parent + ":" + environment().getOrDefault("LD_LIBRARY_PATH", "")
        }.start()
        var registration: AutoCloseable? = null
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            onSpawn(process)
            registration = onCancel { terminate(process) }
            cancelled()
            val out = pool.submit<String> { BackendOutput().capture(process.inputStream.reader(Charsets.UTF_8)) { line ->
                if (line == "progress=end") progress(1.0)
                if (durationSeconds != null && durationSeconds > 0 && line.startsWith("out_time_us=")) line.substringAfter('=').toDoubleOrNull()?.let { progress((it / 1_000_000 / durationSeconds).coerceIn(0.0, 0.99)) }
            } }
            val err = pool.submit<String> { BackendOutput().capture(process.errorStream.reader(Charsets.UTF_8)) }
            process.outputStream.close()
            val code = process.waitFor()
            cancelled()
            val result = ProcessResult(code, out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS))
            if (code != 0) throw ProviderFailure("MEDIA_PROCESS_FAILED", "Media process exited with code $code", backend = BackendFailure(Path.of(arguments.first()).fileName.toString(), code, BackendRedaction.tail(result.stderr, arguments)))
            return result
        } catch (e: InterruptedException) {
            terminate(process); Thread.currentThread().interrupt(); throw CancellationException()
        } finally {
            registration?.close()
            if (process.isAlive) terminate(process)
            runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }; runCatching { process.outputStream.close() }
            pool.shutdownNow()
        }
    }
    fun terminate(process: Process) {
        val interrupted = Thread.interrupted()
        val descendants = process.descendants().use { it.toList() }
        try {
            descendants.reversed().forEach { it.destroy() }; process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); process.destroyForcibly() }
        finally {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

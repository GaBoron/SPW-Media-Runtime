package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.ProcessResult
import dev.gaboron.spwmedia.provider.ProviderFailure
import java.nio.file.Path
import java.util.concurrent.*

/** No shell; both pipes are consumed concurrently and process-tree termination is scoped to the managed child. */
internal object ManagedProcess {
    fun run(arguments: List<String>, cancelled: () -> Unit, onCancel: (() -> Unit) -> AutoCloseable, progress: (Double) -> Unit, durationSeconds: Double? = null): ProcessResult {
        require(arguments.isNotEmpty() && arguments.none { '\u0000' in it })
        cancelled()
        val process = ProcessBuilder(arguments).apply {
            val parent = Path.of(arguments.first()).toAbsolutePath().parent.toString()
            if (System.getProperty("os.name").startsWith("Linux")) environment()["LD_LIBRARY_PATH"] = parent + ":" + environment().getOrDefault("LD_LIBRARY_PATH", "")
        }.start()
        val registration = onCancel { terminate(process) }
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val out = pool.submit<String> { capture(process.inputStream.bufferedReader(), durationSeconds, progress) }
            val err = pool.submit<String> { capture(process.errorStream.bufferedReader(), null) {} }
            process.outputStream.close()
            val code = process.waitFor()
            cancelled()
            val result = ProcessResult(code, out.get(), err.get())
            if (code != 0) throw ProviderFailure("MEDIA_PROCESS_FAILED", "Media process exited with code $code")
            return result
        } catch (e: InterruptedException) {
            terminate(process); Thread.currentThread().interrupt(); throw CancellationException()
        } finally { registration.close(); pool.shutdownNow(); if (process.isAlive) terminate(process) }
    }
    private fun capture(reader: java.io.BufferedReader, durationSeconds: Double?, progress: (Double) -> Unit): String = reader.use {
        val tail = StringBuilder()
        it.lineSequence().forEach { line ->
            if (line == "progress=end") progress(1.0)
            if (durationSeconds != null && durationSeconds > 0 && line.startsWith("out_time_us=")) line.substringAfter('=').toDoubleOrNull()?.let { time -> progress((time / 1_000_000 / durationSeconds).coerceIn(0.0, 0.99)) }
            tail.append(line).append('\n')
            if (tail.length > 1_048_576) tail.delete(0, tail.length - 1_048_576)
        }
        tail.toString()
    }
    fun terminate(process: Process) {
        process.descendants().use { it.forEach { child -> child.destroy() } }
        process.destroy()
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
            process.descendants().use { it.forEach { child -> child.destroyForcibly() } }
            process.destroyForcibly()
        }
    }
}

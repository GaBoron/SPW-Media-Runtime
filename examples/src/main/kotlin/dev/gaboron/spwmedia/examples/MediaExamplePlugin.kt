package dev.gaboron.spwmedia.examples

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import dev.gaboron.spwmedia.protocol.TranscodeOptions
import dev.gaboron.spwmedia.sdk.MediaRuntime
import dev.gaboron.spwmedia.sdk.MediaRuntimeException
import java.nio.file.Path
import java.util.concurrent.Executors

/** Real SDK integration: create/close from the plugin lifecycle; run media operations from user actions. */
class MediaExamplePlugin(context: PluginContext) : SpwPlugin(context) {
    @Volatile private var media: MediaRuntime? = null
    @Volatile private var stopped = false
    /** A caller can display this error if the optional shared runtime is unavailable. */
    @Volatile var connectionError: String? = null
        private set
    private var executor = Executors.newSingleThreadExecutor()
    override fun start() {
        if (executor.isShutdown) executor = Executors.newSingleThreadExecutor()
        connectionError = null
        stopped = false
        executor.execute {
            try {
                val connection = MediaRuntime.connect(pluginContext.pluginId)
                synchronized(this) { if (stopped) connection.close() else media = connection }
            } catch (failure: MediaRuntimeException) { connectionError = failure.detail.code }
        }
    }
    /** Probe without parsing FFprobe output in the consumer. */
    fun inspect(path: Path) = requireNotNull(media).processing.probe(path).result
    /** Common transcode; defaults refuse to overwrite an existing output. */
    fun convert(input: Path, output: Path) = requireNotNull(media).processing.transcode(TranscodeOptions(input.toString(), output.toString(), audioCodec = "flac"))
    /** Advanced invocation retains managed cancellation/status. */
    fun custom(input: Path, output: Path) = requireNotNull(media).processing.execute(listOf("-i", input.toString(), "-af", "loudnorm", output.toString()))
    /** Direct invocation owns its process and keeps the lease alive until it exits. */
    fun directProbe(path: Path) = executor.submit<Int> {
        requireNotNull(media).tools.acquire("ffprobe").use { lease ->
            val process = ProcessBuilder(lease.path.toString(), "-v", "error", "-show_format", path.toString()).inheritIO().start()
            try { process.waitFor() } finally { if (process.isAlive) process.destroyForcibly() }
        }
    }
    override fun stop() { synchronized(this) { stopped = true; media?.close(); media = null }; executor.shutdownNow() }
}

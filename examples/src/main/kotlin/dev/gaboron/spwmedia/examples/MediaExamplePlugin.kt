@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package dev.gaboron.spwmedia.examples

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.gaboron.spwmedia.protocol.TranscodeOptions
import java.nio.file.Path

/** Real SDK integration: create/close from the plugin lifecycle; run media operations from user actions. */
class MediaExamplePlugin(context: PluginContext) : SpwPlugin(context) {
    @Volatile private var operations: ExampleOperations? = null
    /** A caller can display this error if the optional shared runtime is unavailable. */
    val connectionError: String? get() = operations?.connectionError
    override fun start() {
        if (operations != null) return
        current = this
        operations = ExampleOperations(pluginContext.pluginId, WorkshopApi.manager.createConfigManager().getConfig()).also { it.connect() }
    }
    /** Probe without parsing FFprobe output in the consumer. */
    fun inspect(path: Path) = requireNotNull(operations).runtime.processing.probe(path).result
    /** Common transcode; defaults refuse to overwrite an existing output. */
    fun convert(input: Path, output: Path) = requireNotNull(operations).runtime.processing.transcode(TranscodeOptions(input.toString(), output.toString(), audioCodec = "flac"))
    /** Advanced invocation retains managed cancellation/status. */
    fun custom(input: Path, output: Path) = requireNotNull(operations).runtime.processing.execute(listOf("-i", input.toString(), "-af", "loudnorm", output.toString()))
    /** Direct invocation owns its process and keeps the lease alive until it exits. */
    fun directProbe(path: Path) = requireNotNull(operations).directProbe(path)
    override fun stop() { if (current === this) current = null; operations?.close(); operations = null }
    companion object {
        @Volatile private var current: MediaExamplePlugin? = null
        @JvmStatic fun reconnect() { current?.operations?.connect() }
        @JvmStatic fun generate() { current?.operations?.generate() }
        @JvmStatic fun probe() { current?.operations?.probe() }
        @JvmStatic fun transcode() { current?.operations?.transcode() }
        @JvmStatic fun slowTranscode() { current?.operations?.slowTranscode() }
        @JvmStatic fun cancel() { current?.operations?.cancel() }
        @JvmStatic fun play() { current?.operations?.play() }
        @JvmStatic fun frames() { current?.operations?.frames() }
        @JvmStatic fun preview() { current?.operations?.preview() }
        @JvmStatic fun metadata() { current?.operations?.metadata() }
        @JvmStatic fun audio() { current?.operations?.audio() }
        @JvmStatic fun web() { current?.operations?.web() }
        @JvmStatic fun render() { current?.operations?.render() }
        @JvmStatic fun closeSessions() { current?.operations?.closeSessions() }
        @JvmStatic fun showStatus() {
            val result = current?.operations?.result
            WorkshopApi.ui.toast(result?.text ?: "示例未启用", if (result == null || result.isError) WorkshopApi.Ui.ToastType.Error else WorkshopApi.Ui.ToastType.Success)
        }
    }
}

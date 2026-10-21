@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package dev.gaboron.spwmedia.parallel

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi

/** A separate plugin/classloader, compiled against delivered SDK artifacts only. */
class ParallelPlugin(context: PluginContext) : SpwPlugin(context) {
    private var operations: ParallelOperations? = null
    override fun start() {
        if (operations != null) return
        current = this
        operations = ParallelOperations(pluginContext.pluginId, WorkshopApi.manager.createConfigManager().getConfig()).also { it.connect() }
    }
    override fun stop() { if (current === this) current = null; operations?.close(); operations = null }
    companion object {
        @Volatile private var current: ParallelPlugin? = null
        @JvmStatic fun reconnect() { current?.operations?.connect() }
        @JvmStatic fun generate() { current?.operations?.generate() }
        @JvmStatic fun probe() { current?.operations?.probe() }
        @JvmStatic fun transcode() { current?.operations?.transcode() }
        @JvmStatic fun cancel() { current?.operations?.cancel() }
        @JvmStatic fun play() { current?.operations?.play() }
        @JvmStatic fun frames() { current?.operations?.frames() }
        @JvmStatic fun closeSessions() { current?.operations?.closeSessions() }
        @JvmStatic fun showStatus() {
            val result = current?.operations?.result
            WorkshopApi.ui.toast(result?.text ?: "示例未启用", if (result == null || result.isError) WorkshopApi.Ui.ToastType.Error else WorkshopApi.Ui.ToastType.Success)
        }
    }
}

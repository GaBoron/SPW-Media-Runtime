package dev.gaboron.spwmedia.host

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import dev.gaboron.spwmedia.core.*
import dev.gaboron.spwmedia.processing.ProcessingProvider
import dev.gaboron.spwmedia.playback.PlaybackProvider
import dev.gaboron.spwmedia.web.WebProvider
import dev.gaboron.spwmedia.metadata.MetadataProvider
import dev.gaboron.spwmedia.audio.AudioProvider
import dev.gaboron.spwmedia.rendering.RenderingProvider
import java.nio.file.*

/** Host adapter owns only composition, installation paths and SPW lifecycle. */
class MediaRuntimePlugin(context: PluginContext) : SpwPlugin(context) {
    private var endpoint: RuntimeEndpoint? = null
    override fun start() {
        if (endpoint != null) return
        val supplied = Path.of(pluginContext.pluginPath).toAbsolutePath()
        val contextRoot = if (Files.isDirectory(supplied)) supplied else supplied.parent
        val ownCode = Path.of(javaClass.protectionDomain.codeSource.location.toURI())
        val codeRoot = if (Files.isDirectory(ownCode) && ownCode.fileName.toString() == "classes") ownCode.parent else ownCode.parent
        val root = listOf(contextRoot, codeRoot).firstOrNull { Files.isDirectory(it.resolve("native")) } ?: contextRoot
        val storage = runtimeStorage()
        val classpath = buildList {
            Files.list(root).use { stream -> stream.filter { it.toString().endsWith(".jar") }.forEach { add(it.toString()) } }
            val lib = root.resolve("lib")
            if (Files.isDirectory(lib)) Files.list(lib).use { stream -> stream.filter { it.toString().endsWith(".jar") }.forEach { add(it.toString()) } }
            // Worker JVM needs Kotlin; reuse the host's existing Kotlin location rather than bundling another copy.
            add(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI().let { Path.of(it).toString() })
        }.joinToString(java.io.File.pathSeparator)
        val assets = NativeAssets(root.resolve("native"), storage.resolve("native").resolve(pluginContext.pluginVersion))
        val engine = RuntimeEngine(listOf(ProcessingProvider(), PlaybackProvider(), WebProvider(), MetadataProvider(), AudioProvider(), RenderingProvider()), assets, storage, emptyMap(), workerCommand = { mode ->
            when (mode) {
                "playback" -> WorkerCommands.playback(assets, classpath)
                "web", "capture" -> assets.find("platform")?.let { listOf(it.toString(), mode) }
                else -> null
            }
        })
        try {
            val identity = CallerIdentity(javaClass.classLoader)
            endpoint = RuntimeEndpoint(engine, identity::current)
        } catch (e: Throwable) { engine.close(); throw e }
    }
    override fun stop() { endpoint?.close(); endpoint = null }
}

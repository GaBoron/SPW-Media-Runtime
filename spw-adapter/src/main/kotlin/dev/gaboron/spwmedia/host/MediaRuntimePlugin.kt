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
        val home = System.getProperty("user.home")
        val storage = if (System.getProperty("os.name").startsWith("Windows")) Path.of(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "SPW Media Runtime")
        else Path.of(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "spw-media-runtime")
        val classpath = buildList {
            Files.list(root).use { stream -> stream.filter { it.toString().endsWith(".jar") }.forEach { add(it.toString()) } }
            val lib = root.resolve("lib")
            if (Files.isDirectory(lib)) Files.list(lib).use { stream -> stream.filter { it.toString().endsWith(".jar") }.forEach { add(it.toString()) } }
            // Worker JVM needs Kotlin; reuse the host's existing Kotlin location rather than bundling another copy.
            add(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI().let { Path.of(it).toString() })
        }.joinToString(java.io.File.pathSeparator)
        val assets = NativeAssets(root.resolve("native"), storage.resolve("native").resolve(pluginContext.pluginVersion))
        val commands = buildMap {
            assets.find("mpv")?.let { library -> put("playback", listOf(Path.of(System.getProperty("java.home"), "bin", if (assets.platform.startsWith("windows")) "java.exe" else "java").toString(), "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "dev.gaboron.spwmedia.worker.WorkerMainKt", library.toString())) }
            assets.find("platform")?.let { helper -> listOf("web", "capture").forEach { put(it, listOf(helper.toString(), it)) } }
        }
        val engine = RuntimeEngine(listOf(ProcessingProvider(), PlaybackProvider(), WebProvider(), MetadataProvider(), AudioProvider(), RenderingProvider()), assets, storage, commands)
        try {
            val identity = CallerIdentity(javaClass.classLoader)
            endpoint = RuntimeEndpoint(engine, identity::current)
        } catch (e: Throwable) { engine.close(); throw e }
    }
    override fun stop() { endpoint?.close(); endpoint = null }
}

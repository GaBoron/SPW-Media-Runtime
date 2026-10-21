package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.provider.Assets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Immutable package lookup. A running runtime pins its install tree; hot replacement is unsupported. */
class NativeAssets(private val root: Path, private val cache: Path? = null) : Assets, AutoCloseable {
    override val platform: String = when {
        System.getProperty("os.arch").lowercase() !in setOf("amd64", "x86_64") -> "unsupported"
        System.getProperty("os.name").startsWith("Windows") -> "windows-x64"
        System.getProperty("os.name").startsWith("Linux") -> "linux-x64"
        else -> "unsupported"
    }
    private val directory = root.resolve(platform).toAbsolutePath().normalize()
    private val manifest = Properties().apply {
        val path = directory.resolve("assets.properties")
        if (Files.isRegularFile(path)) Files.newInputStream(path).use(::load)
    }
    private var prepared: NativeAssetCache? = null
    private var closed = false
    override fun locate(name: String): Path? {
        val relative = manifest.getProperty("$name.path") ?: return null
        val target = directory.resolve(relative).normalize()
        if (!target.startsWith(directory) || !Files.isRegularFile(target)) return null
        return target
    }
    @Synchronized override fun find(name: String): Path? {
        check(!closed) { "Native assets are closed" }
        val target = locate(name) ?: return null
        val pinned = if (cache == null) target else {
            val active = prepared ?: NativeAssetCache(directory, cache.resolve(platform)) { recycle(it, locate("recycle")) }.also { prepared = it }
            active.prepare(name, directory.relativize(target))
        }
        if (platform == "linux-x64" && name in setOf("ffmpeg", "ffprobe", "platform", "worker")) pinned.toFile().setExecutable(true, true)
        return pinned
    }
    @Synchronized override fun close() { if (closed) return; closed = true; prepared?.close(); prepared = null }
    @Synchronized internal fun track(process: Process) { prepared?.track(process) }
    @Synchronized internal fun externalTool(): AutoCloseable? = prepared?.externalTool()
    override fun version(name: String): String = manifest.getProperty("$name.version", "unknown")
}

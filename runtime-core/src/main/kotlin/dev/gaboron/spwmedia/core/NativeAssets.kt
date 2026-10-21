package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.provider.Assets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Immutable package lookup. A running runtime pins its install tree; hot replacement is unsupported. */
class NativeAssets(private val root: Path, private val cache: Path? = null) : Assets {
    override val platform: String = when {
        System.getProperty("os.arch").lowercase() !in setOf("amd64", "x86_64") -> "unsupported"
        System.getProperty("os.name").startsWith("Windows") -> "windows-x64"
        System.getProperty("os.name").startsWith("Linux") -> "linux-x64"
        else -> "unsupported"
    }
    private val directory = root.resolve(platform).toAbsolutePath().normalize()
    private val installation = runCatching { Files.getLastModifiedTime(directory.resolve("assets.properties")).toMillis().toString() }.getOrDefault("missing")
    private val manifest = Properties().apply {
        val path = directory.resolve("assets.properties")
        if (Files.isRegularFile(path)) Files.newInputStream(path).use(::load)
    }
    override fun find(name: String): Path? {
        val relative = manifest.getProperty("$name.path") ?: return null
        val target = directory.resolve(relative).normalize()
        if (!target.startsWith(directory) || !Files.isRegularFile(target)) return null
        val pinned = if (cache == null) target else pin().resolve(relative)
        if (platform == "linux-x64" && name in setOf("ffmpeg", "ffprobe", "platform")) pinned.toFile().setExecutable(true, true)
        return pinned
    }
    @Synchronized private fun pin(): Path {
        val destination = cache!!.resolve(platform).resolve(installation)
        if (!Files.isRegularFile(destination.resolve(".complete"))) {
            Files.walk(directory).use { stream -> stream.forEach { source ->
                val output = destination.resolve(directory.relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(output)
                else Files.copy(source, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } }
            Files.writeString(destination.resolve(".complete"), "complete")
        }
        return destination
    }
    override fun version(name: String): String = manifest.getProperty("$name.version", "unknown")
}

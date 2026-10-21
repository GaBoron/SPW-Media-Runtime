package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.BuildInfo
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.Properties

/** Maintainer command: preview is the default; unknown generations are never eligible for automatic recycling. */
fun main(arguments: Array<String>) {
    val storage = arguments.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(Path::of) ?: runtimeStorage()
    val selected = arguments.getOrNull(1).orEmpty()
    val apply = arguments.getOrNull(2) == "true"
    require(!apply || selected.matches(Regex("[a-f0-9]{64}"))) { "Applying cleanup requires one exact cacheGeneration from the preview" }
    val root = storage.resolve("native").toAbsolutePath().normalize()
    if (!Files.isDirectory(root)) { println("No native cache at $root"); return }
    Files.walk(root, 4).use { paths -> paths.filter { it.fileName.toString() == ".generation" }.toList().forEach { marker ->
        val directory = marker.parent
        val identity = directory.fileName.toString()
        if (selected.isNotEmpty() && selected != identity) return@forEach
        val status = inspectCacheGeneration(directory, BuildInfo.VERSION, apply)
        println("$identity: $status ($directory)")
    } }
    println("Directories without generation metadata are retained; inspect legacy caches with all hosts stopped.")
}

internal fun inspectCacheGeneration(directory: Path, currentVersion: String, apply: Boolean, recycler: (Path) -> Boolean = { recycle(it) }): String {
    if (Files.isSymbolicLink(directory) || directory.toRealPath() != directory.toAbsolutePath().normalize()) return "unknown path"
    return generationGate(directory) {
        val marker = directory.resolve(".generation")
        if (!Files.isRegularFile(marker) || Files.isSymbolicLink(marker)) return@generationGate "unknown metadata"
        val metadata = Properties().apply { Files.newInputStream(marker).use(::load) }
        val identity = metadata.getProperty("identity", "")
        if (!identity.matches(Regex("[a-f0-9]{64}")) || identity != directory.fileName.toString() || metadata.getProperty("format") != "1" || !metadata.getProperty("version", "").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return@generationGate "unknown metadata"
        if (metadata.getProperty("version") == currentVersion) return@generationGate "current version"
        val channels = mutableListOf<FileChannel>()
        val locks = mutableListOf<java.nio.channels.FileLock>()
        try {
            var status = "candidate"
            Files.list(directory).use { files -> if (files.anyMatch { it.fileName.toString().startsWith(".process-") && processMayBeAlive(it) }) status = "active or unknown process" }
            Files.list(directory).use { files -> if (files.anyMatch { it.fileName.toString().startsWith(".external-tool-") }) status = "unknown caller-owned tool process" }
            Files.list(directory).use { entries -> entries.filter { it.fileName.toString().startsWith(".lease-") }.forEach { file ->
                if (Files.isSymbolicLink(file)) { status = "unknown lease"; return@forEach }
                val channel = FileChannel.open(file, StandardOpenOption.WRITE); channels.add(channel)
                val held = runCatching { channel.tryLock() }.getOrNull()
                if (held == null) status = "active or unknown lease" else locks.add(held)
            } }
            if (apply && status == "candidate") {
                locks.forEach { it.release() }; locks.clear(); channels.forEach { it.close() }; channels.clear()
                status = if (recycler(directory)) "recycled" else "recycle pending; retained for retry"
            }
            status
        } finally { locks.forEach { it.release() }; channels.forEach { it.close() } }
    }
}

fun runtimeStorage(): Path {
    val home = System.getProperty("user.home")
    return if (System.getProperty("os.name").startsWith("Windows")) Path.of(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "SPW Media Runtime")
    else Path.of(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "spw-media-runtime")
}

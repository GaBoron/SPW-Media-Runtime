package dev.gaboron.spwmedia.core

import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

/** Tracks only runtime-created mappings. Caller media and persistent profiles never enter this directory. */
internal class BufferStorage(private val root: Path, private val recycler: (Path) -> Boolean = { recycle(it) }) : AutoCloseable {
    val directory: Path
    private val channel: FileChannel
    private val lease: java.nio.channels.FileLock
    init {
        Files.createDirectories(root)
        recoverBufferStorage(root, recycler)
        directory = root.resolve("run-${UUID.randomUUID()}")
        Files.createDirectories(directory)
        recordProcess(directory.resolve(".owner"), ProcessHandle.current())
        channel = FileChannel.open(directory.resolve(".active"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        lease = channel.lock()
    }
    fun release(path: Path) {
        require(path.parent == directory && path.fileName.toString().endsWith(".buffer"))
        if (!recycler(path)) Files.writeString(directory.resolve("${path.fileName}.pending"), "recycle pending\n")
    }
    fun track(process: Process) = trackProcess(directory, process, recycler)
    @Synchronized override fun close() {
        if (!lease.isValid) return
        lease.release(); channel.close()
        val hasBuffers = Files.list(directory).use { it.anyMatch { path -> path.fileName.toString().endsWith(".buffer") || path.fileName.toString().startsWith(".process-") } }
        if (!hasBuffers) recycler(directory)
    }
}

internal fun recoverBufferStorage(root: Path, recycler: (Path) -> Boolean = { recycle(it) }) {
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) return
        Files.list(root).use { runs -> runs.filter { it.fileName.toString().startsWith("run-") }.limit(16).forEach { run ->
            if (!Files.isDirectory(run) || Files.isSymbolicLink(run) || !Files.isRegularFile(run.resolve(".owner")) || processMayBeAlive(run.resolve(".owner"))) return@forEach
            val active = run.resolve(".active")
            if (!Files.isRegularFile(active) || Files.isSymbolicLink(active)) return@forEach
            var eligible = true
            FileChannel.open(active, StandardOpenOption.WRITE).use { channel ->
                val held = runCatching { channel.tryLock() }.getOrNull() ?: return@forEach
                held.use {
                    Files.list(run).use { files -> if (files.anyMatch { it.fileName.toString().startsWith(".process-") && processMayBeAlive(it) }) eligible = false }
                }
            }
            if (eligible) recycler(run)
        } }
}

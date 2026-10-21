package dev.gaboron.spwmedia.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal fun recordProcess(path: Path, process: ProcessHandle) {
    Files.writeString(path, "pid=${process.pid()}\nstarted=${process.info().startInstant().map { it.toString() }.orElse("unknown")}\n")
}

/** Unknown metadata is treated as active. Start time prevents a reused PID from protecting a stale owner. */
internal fun processMayBeAlive(path: Path): Boolean = runCatching {
    val properties = Properties().apply { Files.newInputStream(path).use(::load) }
    val pid = properties.getProperty("pid").toLong()
    val expected = properties.getProperty("started")
    if (expected == null || expected == "unknown") return@runCatching true
    val process = ProcessHandle.of(pid).orElse(null) ?: return@runCatching false
    process.isAlive && process.info().startInstant().map { it.toString() == expected }.orElse(true)
}.getOrDefault(true)

internal fun trackProcess(directory: Path, process: Process, recycler: (Path) -> Boolean = { recycle(it) }) {
    val record = directory.resolve(".process-${process.pid()}-${java.util.UUID.randomUUID()}")
    recordProcess(record, process.toHandle())
    process.onExit().thenRun { recycler(record) }
}

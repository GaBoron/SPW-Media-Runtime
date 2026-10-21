package dev.gaboron.spwmedia.core

import java.nio.file.Files
import java.nio.file.Path

/** No permanent-delete fallback. Callers must first verify ownership and absence of live users. */
internal fun recycle(path: Path, helper: Path? = System.getProperty("spw.media.recycle.helper")?.let(Path::of)): Boolean {
    if (!Files.exists(path)) return true
    val recycled = runCatching {
        val target = path.toAbsolutePath().normalize()
        require(target.parent != null && target != Path.of(System.getProperty("user.home")).toAbsolutePath().normalize() && target != Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize())
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            if (helper == null || !Files.isRegularFile(helper)) return@runCatching false
            listOf(helper.toAbsolutePath().toString(), target.toString())
        } else listOf("gio", "trash", "--", target.toString())
        val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try { process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0 && !Files.exists(target) }
        finally { if (process.isAlive) ManagedProcess.terminate(process) }
    }.getOrDefault(false)
    if (!recycled) System.getLogger("SPW Media Runtime").log(System.Logger.Level.WARNING, "Recycle Bin unavailable or file still in use; retained managed path: $path")
    return recycled
}

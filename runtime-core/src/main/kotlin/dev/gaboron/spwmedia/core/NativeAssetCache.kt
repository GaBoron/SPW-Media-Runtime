package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.BuildInfo
import dev.gaboron.spwmedia.provider.ProviderFailure
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Immutable, dependency-complete preparation. One lease protects the generation used by this runtime. */
internal class NativeAssetCache(private val source: Path, parent: Path, private val recycler: (Path) -> Boolean = { recycle(it) }) : AutoCloseable {
    private val manifest = Properties().apply { Files.newInputStream(source.resolve("asset-manifest.properties")).use(::load) }
    private val identity = manifest.getProperty("identity").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    val directory: Path = parent.resolve(identity).toAbsolutePath().normalize()
    private val leasePath: Path
    private val lease: FileChannel
    private val lock: java.nio.channels.FileLock
    private val ready = mutableSetOf<String>()
    private val verified = mutableSetOf<String>()
    init {
        Files.createDirectories(directory.parent)
        leasePath = directory.resolve(".lease-${UUID.randomUUID()}")
        val held = generationGate(directory) {
            Files.createDirectories(directory)
            val channel = FileChannel.open(leasePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            val held = channel.lock()
            Files.writeString(directory.resolve(".generation"), "format=1\nversion=${BuildInfo.VERSION}\nidentity=$identity\n")
            channel to held
        }
        lease = held.first; lock = held.second
    }
    @Synchronized fun prepare(name: String, entry: Path): Path {
        check(lock.isValid)
        if (name in ready) return directory.resolve(entry)
        val files = manifest.getProperty("asset.$name.files")?.split(',') ?: throw ProviderFailure("DEPENDENCY_MISSING", "Native dependency manifest is incomplete")
        require(entry.toString().replace('\\', '/') in files)
        generationGate(directory) {
            for (relative in files) {
                val input = safeResolve(source, relative)
                val output = safeResolve(directory, relative)
                require(Files.isRegularFile(input) && !Files.isSymbolicLink(input))
                if (Files.exists(output)) {
                    if (relative !in verified && !valid(output, relative)) throw ProviderFailure("DEPENDENCY_MISSING", "Native cache content is invalid; close consumers and inspect the cache")
                } else {
                    Files.createDirectories(output.parent)
                    val staging = output.resolveSibling("${output.fileName}.partial-${UUID.randomUUID()}")
                    try {
                        Files.copy(input, staging)
                        check(valid(staging, relative)) { "Native dependency copy was incomplete" }
                        try { Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE) }
                        catch (_: AtomicMoveNotSupportedException) { Files.move(staging, output) }
                    } finally { if (Files.exists(staging)) recycler(staging) }
                }
                verified.add(relative)
            }
            Files.writeString(directory.resolve(".generation"), "format=1\nversion=${BuildInfo.VERSION}\nidentity=$identity\n")
        }
        ready.add(name)
        return directory.resolve(entry)
    }
    private fun valid(path: Path, relative: String): Boolean {
        if (Files.isSymbolicLink(path) || Files.size(path) != manifest.getProperty("file.$relative.size")?.toLongOrNull()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) } == manifest.getProperty("file.$relative.digest")
    }
    @Synchronized fun track(process: Process) = trackProcess(directory, process, recycler)
    @Synchronized fun externalTool(): AutoCloseable {
        check(lock.isValid)
        val marker = directory.resolve(".external-tool-${UUID.randomUUID()}")
        recordProcess(marker, ProcessHandle.current())
        return AutoCloseable { recycler(marker) }
    }
    @Synchronized override fun close() { if (lock.isValid) lock.release(); if (lease.isOpen) lease.close(); recycler(leasePath) }
}

internal fun <T> generationGate(directory: Path, action: () -> T): T {
    val guard = directory.parent.resolve(".guard-${directory.fileName}")
    FileChannel.open(guard, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        while (true) {
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lock != null) return lock.use { action() }
            Thread.sleep(10)
        }
    }
}

internal fun safeResolve(root: Path, relative: String): Path {
    val base = root.toAbsolutePath().normalize()
    val path = base.resolve(relative).normalize()
    require(path != base && path.startsWith(base) && !Path.of(relative).isAbsolute) { "Path is outside the managed directory" }
    var current = path
    while (current != base) { require(!Files.isSymbolicLink(current)); current = current.parent }
    return path
}

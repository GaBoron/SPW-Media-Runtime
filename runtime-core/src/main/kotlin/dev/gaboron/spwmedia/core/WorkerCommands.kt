package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.provider.Assets
import dev.gaboron.spwmedia.provider.ProviderFailure
import java.nio.file.Files
import java.nio.file.Path

/** Native bootstrap reuses an installed JVM image, including images without bin/java. */
object WorkerCommands {
    fun playback(assets: Assets, classpath: String, javaHome: Path = Path.of(System.getProperty("java.home"))): List<String>? {
        val runtime = javaHome.resolve(if (assets.platform.startsWith("windows")) "bin/server/jvm.dll" else "lib/server/libjvm.so")
        if (!Files.isRegularFile(runtime)) throw ProviderFailure("DEPENDENCY_MISSING", "The host JVM library is unavailable")
        val launcher = assets.find("worker") ?: return null
        val library = assets.find("mpv") ?: return null
        return listOf(launcher.toString(), javaHome.toAbsolutePath().toString(), classpath, library.toString())
    }
}

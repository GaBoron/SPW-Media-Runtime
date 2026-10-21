package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.provider.Assets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkerCommandsTest {
    @Test fun runtimeWithoutJavaExecutableUsesNativeBootstrap() {
        val root = Files.createTempDirectory("spw-media-jvm-image-")
        try {
            val home = root.resolve("runtime")
            Files.createDirectories(home.resolve("bin/server"))
            Files.writeString(home.resolve("bin/server/jvm.dll"), "runtime fixture")
            val prepared = mutableListOf<String>()
            val assets = object : Assets {
                override val platform = "windows-x64"
                override fun find(name: String): Path { prepared.add(name); return root.resolve(name) }
                override fun version(name: String) = "test"
            }
            val command = assertNotNull(WorkerCommands.playback(assets, "classes and jars", home))
            assertFalse(Files.exists(home.resolve("bin/java.exe")))
            assertEquals(listOf(root.resolve("worker").toString(), home.toString(), "classes and jars", root.resolve("mpv").toString()), command)
            assertEquals(listOf("worker", "mpv"), prepared)
        } finally { recycle(root) }
    }
}

package dev.gaboron.spwmedia.examples

import java.nio.file.*
import java.util.UUID

/** Only explicit actions allocate test media; outputs are unique and never overwrite user files. */
class ExampleSamples : AutoCloseable {
    val directory: Path = Files.createTempDirectory("spw-media-example-")
    val video: Path = directory.resolve("sample.mp4")
    fun output(extension: String): Path = directory.resolve("${UUID.randomUUID()}.$extension")
    fun videoArguments(): List<String> {
        check(!Files.exists(video)) { "测试视频已存在，请直接使用它" }
        return listOf("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-t", "2", "-an", "-c:v", "mpeg4", "-q:v", "5", video.toString())
    }
    fun rawFrame(): Path = output("bgra").also { Files.write(it, byteArrayOf(0, 0, -1, -1, 0, -1, 0, -1, -1, 0, 0, -1, -1, -1, -1, -1)) }
    override fun close() {
        // The SDK consumer has no dependency on Runtime internals. Its temporary outputs remain at this exact path.
        System.getLogger("SPW Media Example").log(System.Logger.Level.INFO, "Generated media retained for inspection: $directory")
    }
}

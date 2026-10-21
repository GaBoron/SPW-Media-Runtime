package dev.gaboron.spwmedia.examples

import java.nio.file.*
import java.util.UUID

/** Only explicit actions allocate demo media; outputs are unique and never overwrite user files. */
class ExampleSamples : AutoCloseable {
    val directory: Path = Files.createTempDirectory("spw-media-example-")
    val video: Path = directory.resolve("sample.mp4")
    val audio: Path = directory.resolve("sample.flac")
    fun output(extension: String): Path = directory.resolve("${UUID.randomUUID()}.$extension")
    fun videoArguments(): List<String> {
        check(!Files.exists(video)) { "演示素材已存在，可留空输入媒体路径直接使用" }
        return listOf("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono",
            "-map", "0:v", "-t", "2", "-an", "-c:v", "mpeg4", "-q:v", "5", video.toString(),
            "-map", "1:a", "-t", "2", "-vn", "-c:a", "flac", "-metadata", "title=SPW Media Example", audio.toString())
    }
    fun rawFrame(): Path = output("bgra").also { Files.write(it, byteArrayOf(0, 0, -1, -1, 0, -1, 0, -1, -1, 0, 0, -1, -1, -1, -1, -1)) }
    override fun close() {
        // The SDK consumer has no dependency on Runtime internals. Its temporary outputs remain at this exact path.
        System.getLogger("SPW Media Example").log(System.Logger.Level.INFO, "Generated media retained for inspection: $directory")
    }
}

@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package dev.gaboron.spwmedia.parallel

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import dev.gaboron.spwmedia.protocol.PlayerOptions
import dev.gaboron.spwmedia.sdk.*
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.*

/** Deliberately small independent consumer: no Runtime implementation or first-example code. */
class ParallelOperations(private val pluginId: String, private val config: ConfigHelper) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "Parallel media example").apply { isDaemon = true } }
    private var connecting = false
    @Volatile private var closed = false
    @Volatile private var client: MediaRuntime? = null
    @Volatile private var task: RuntimeTask<*>? = null
    @Volatile private var player: Player? = null
    private val directory by lazy { Files.createTempDirectory("spw-media-parallel-") }
    private val sample get() = directory.resolve("sample.mp4")
    @Volatile var status = "尚未连接"
        private set
    private val media get() = client ?: error("请启用 Runtime 并重新启用本示例")
    private fun report(message: String) {
        status = message.take(4096)
        if (!closed) synchronized(config) { config.set("status", status); config.save() }
    }
    private fun failure(error: Throwable) {
        var cause = error
        while (cause is CompletionException || cause is ExecutionException) cause = cause.cause ?: break
        val detail = (cause as? MediaRuntimeException)?.detail
        report(if (detail != null) detail.code + ": " + detail.message else cause.message ?: cause.javaClass.simpleName)
    }
    private fun dispatch(action: () -> Unit) {
        if (closed) return
        try { executor.execute { if (!closed) try { action() } catch (error: Throwable) { failure(error) } } }
        catch (_: RejectedExecutionException) {}
    }
    private fun input(): Path {
        config.reload()
        val explicit = config.get("input", "").takeIf { it.isNotBlank() }
        val path = explicit?.let(Path::of) ?: sample.takeIf(Files::isRegularFile)
            ?: config.get("generatedVideo", "").takeIf { it.isNotBlank() }?.let(Path::of)
            ?: error("先点击生成测试视频并等待完成，或填写输入路径")
        require(Files.isRegularFile(path)) { if (explicit == null) "测试视频不存在，请重新生成并等待完成" else "输入媒体不存在：$path" }
        return path
    }
    private fun <T> submit(label: String, create: () -> RuntimeTask<T>, complete: (T) -> Unit) {
        check(task == null) { "已有任务，请先取消或等待完成" }
        val active = create(); task = active; report(label + "：" + active.id)
        active.result.whenComplete { value, error ->
            if (task === active) task = null
            if (error != null) failure(error) else try { complete(value) } catch (failure: Throwable) { this.failure(failure) }
        }
    }
    fun connect() = dispatch {
        if (connecting) return@dispatch
        client?.let { existing ->
            if (runCatching { existing.capabilities() }.isSuccess) { report("已连接独立消费者"); return@dispatch }
            runCatching { task?.cancel() }; task = null
            runCatching { player?.close() }; player = null
            runCatching { existing.close() }; client = null
        }
        connecting = true
        connectAttempt(0)
    }
    private fun connectAttempt(attempt: Int) {
        if (closed) return
        try {
            val connected = MediaRuntime.connect(pluginId)
            synchronized(this) { if (closed) { connected.close(); return }; client = connected }
            connecting = false
            report("已连接独立消费者；能力：" + connected.capabilities().filter { it.available }.joinToString { it.id })
        } catch (error: Throwable) {
            if ((error as? MediaRuntimeException)?.detail?.code == "RUNTIME_UNAVAILABLE" && attempt < 29 && !closed) {
                report("等待 Runtime 启用（${attempt + 1}/30）")
                try { executor.schedule({ connectAttempt(attempt + 1) }, 1, TimeUnit.SECONDS) }
                catch (_: RejectedExecutionException) { connecting = false }
            } else { connecting = false; failure(error) }
        }
    }
    fun generate() = dispatch {
        require(!Files.exists(sample)) { "测试视频已存在，可直接使用" }
        submit("生成无声测试视频", { media.processing.execute(listOf("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-t", "2", "-an", "-c:v", "mpeg4", sample.toString())) }) {
            synchronized(config) { config.set("generatedVideo", sample.toString()); config.save() }
            report("测试视频：" + sample)
        }
    }
    fun probe() = dispatch { submit("探测", { media.processing.probe(input()) }) { report("格式=" + it.format + "；流=" + it.streams.map { stream -> stream.type }) } }
    fun transcode() = dispatch {
        val destination = directory.resolve(UUID.randomUUID().toString() + ".mp4")
        submit("实时转码，可取消", { media.processing.execute(listOf("-re", "-stream_loop", "-1", "-i", input().toString(), "-t", "120", "-an", "-c:v", "mpeg4", destination.toString())) }) { report("转码完成：" + destination) }
    }
    fun cancel() = dispatch { report(if (task?.cancel() == true) "已请求取消，等待终态" else "没有可取消任务") }
    fun play() = dispatch {
        player?.close(); player = null
        val path = input()
        submit("创建独立播放器", { media.playback.open(PlayerOptions(frameWidth = 320, frameHeight = 180, options = mapOf("audio" to "no", "loop-file" to "inf"))) }) { created ->
            synchronized(this) { if (closed) { created.close(); return@submit }; player = created }
            created.load(path.toString()).whenComplete { _, error -> if (error == null) report("静音共享帧播放已启动：" + created.id) else { created.close(); failure(error) } }
        }
    }
    fun frames() = dispatch {
        requireNotNull(player) { "先创建播放器" }.frames().use { buffer ->
            val frame = buffer.latest()
            report(if (frame == null) "暂无视频帧，可稍后重试" else "视频帧序号=" + frame.sequence + "；字节=" + frame.bytes.size)
        }
    }
    fun closeSessions() = dispatch { task?.cancel(); player?.close(); player = null; report("本消费者会话已关闭") }
    override fun close() {
        synchronized(this) { if (closed) return; closed = true }
        runCatching { task?.cancel() }; runCatching { player?.close() }; runCatching { client?.close() }
        executor.shutdownNow()
    }
}

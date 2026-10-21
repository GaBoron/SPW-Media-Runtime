@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package dev.gaboron.spwmedia.parallel

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import dev.gaboron.spwmedia.protocol.PlayerOptions
import dev.gaboron.spwmedia.sdk.*
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.*

data class ActionStatus(val text: String, val isError: Boolean = false)

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
    @Volatile var result = ActionStatus("尚未连接")
        private set
    val status get() = result.text
    private val media get() = client ?: error("请启用 Runtime，再在「连接与会话」中点击「重新连接 Runtime」")
    @Synchronized private fun report(message: String, isError: Boolean = false) {
        result = ActionStatus(message.take(4096), isError)
        if (!closed) synchronized(config) { config.set("status", status); config.save() }
    }
    private fun failure(error: Throwable) {
        var cause = error
        while (cause is CompletionException || cause is ExecutionException) cause = cause.cause ?: break
        val detail = (cause as? MediaRuntimeException)?.detail
        report(if (detail != null) detail.code + ": " + detail.message else cause.message ?: cause.javaClass.simpleName, isError = true)
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
            ?: error("请先点击「创建演示素材」并等待完成，或填写输入媒体路径")
        require(Files.isRegularFile(path)) { if (explicit == null) "演示视频不存在，请重新创建素材并等待完成" else "输入媒体不存在：$path" }
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
        require(!Files.exists(sample)) { "演示素材已存在，可留空输入媒体路径直接使用" }
        submit("创建演示素材", { media.processing.execute(listOf("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-t", "2", "-an", "-c:v", "mpeg4", sample.toString())) }) {
            synchronized(config) { config.set("generatedVideo", sample.toString()); config.save() }
            report("演示视频：" + sample)
        }
    }
    fun probe() = dispatch { submit("探测", { media.processing.probe(input()) }) { report("格式=" + it.format + "；流=" + it.streams.map { stream -> stream.type }) } }
    fun transcode() = dispatch {
        val destination = directory.resolve(UUID.randomUUID().toString() + ".mp4")
        submit("实时转码，可取消", { media.processing.execute(listOf("-re", "-stream_loop", "-1", "-i", input().toString(), "-t", "120", "-an", "-c:v", "mpeg4", destination.toString())) }) { report("转码完成：" + destination) }
    }
    fun cancel() = dispatch { report(if (task?.cancel() == true) "已请求取消，正在等待任务结束" else "没有可取消任务") }
    fun play() = dispatch {
        player?.close(); player = null
        val path = input()
        submit("创建独立播放器", { media.playback.open(PlayerOptions(frameWidth = 320, frameHeight = 180, options = mapOf("audio" to "no", "loop-file" to "inf"))) }) { created ->
            synchronized(this) { if (closed) { created.close(); return@submit }; player = created }
            created.load(path.toString()).whenComplete { _, error -> if (error == null) report("静音共享帧播放已启动：" + created.id) else { created.close(); failure(error) } }
        }
    }
    fun frames() = dispatch {
        val producer = requireNotNull(player) { "请先点击「开始静音播放」" }
        producer.frames().use { buffer ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var frame: MediaBuffer? = null
            while (frame == null && System.nanoTime() < deadline) { frame = buffer.latest(); if (frame == null) Thread.sleep(20) }
            if (frame == null) producer.snapshot().error?.let { throw MediaRuntimeException(it) }
            val received = requireNotNull(frame) { "尚未收到视频帧，请稍后重试" }
            check(received.bytes.size == received.width * received.height * 4)
            report("视频帧序号=" + received.sequence + "；字节=" + received.bytes.size)
        }
    }
    fun closeSessions() = dispatch { task?.cancel(); player?.close(); player = null; report("本消费者会话已关闭") }
    override fun close() {
        synchronized(this) { if (closed) return; closed = true }
        runCatching { task?.cancel() }; runCatching { player?.close() }; runCatching { client?.close() }
        executor.shutdownNow()
    }
}

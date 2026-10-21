@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package dev.gaboron.spwmedia.examples

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.sdk.*
import java.nio.file.*
import java.util.concurrent.*

data class ActionStatus(val text: String, val isError: Boolean = false)

/** Public-SDK workflows; all media writes go to an owned temporary directory. */
class ExampleOperations(private val pluginId: String, private val config: ConfigHelper) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "Media example actions").apply { isDaemon = true } }
    @Volatile private var stopped = false
    @Volatile private var connection: ExampleConnection? = null
    @Volatile private var active: RuntimeTask<*>? = null
    @Volatile private var player: Player? = null
    @Volatile private var browser: Browser? = null
    @Volatile private var capture: AudioCapture? = null
    @Volatile private var preview: ExamplePreview? = null
    private val directProcesses = ConcurrentHashMap.newKeySet<Process>()
    private var samples: ExampleSamples? = null
    @Volatile var result = ActionStatus("尚未连接")
        private set
    val status get() = result.text
    @Volatile var connectionError: String? = null
        private set
    val runtime: MediaRuntime get() = connection?.current ?: error("请启用 Runtime，再在「连接与会话」中点击「重新连接 Runtime」")
    fun connect() = dispatch {
        val existing = connection
        if (existing != null) existing.reconnect()
        else connection = ExampleConnection(pluginId, {
            closeOwnedSessions()
            directProcesses.forEach { terminateDirect(it) }
            active = null
        }) { state ->
            if (!stopped) {
                connectionError = state.error?.code
                report(when (state.status) {
                    ConnectionStatus.CONNECTED -> "已连接 Runtime；连接代次=${state.generation}，请按需创建媒体会话"
                    ConnectionStatus.WAITING -> "等待 Runtime 启用或连接清理"
                    ConnectionStatus.UNAVAILABLE -> "${state.error?.code}：${state.error?.message}；可显式重连"
                    ConnectionStatus.CLOSED -> "连接已关闭"
                }, state.status == ConnectionStatus.UNAVAILABLE)
            }
        }
    }
    @Synchronized private fun report(value: String, isError: Boolean = false) {
        result = ActionStatus(value.take(4096), isError)
        if (!stopped) synchronized(config) { config.set("status", status); config.save() }
    }
    private fun failure(error: Throwable) {
        var cause = error
        while (cause is CompletionException || cause is ExecutionException) cause = cause.cause ?: break
        val detail = MediaRuntimeException.unwrap(cause)?.detail
        report(if (detail == null) "操作失败：${cause.message ?: cause.javaClass.simpleName}" else "${detail.code}: ${detail.message}" + (detail.backend?.let { "\n${it.backend} exit=${it.exitCode}\n${it.stderrTail.takeLast(1800)}" } ?: ""), isError = true)
    }
    private fun dispatch(action: () -> Unit) {
        if (stopped) return
        try { executor.execute { if (!stopped) try { action() } catch (error: Throwable) { failure(error) } } }
        catch (_: RejectedExecutionException) { /* stop won the race */ }
    }
    private fun input(): Path {
        config.reload()
        val explicit = config.get("input", "").takeIf { it.isNotBlank() }
        val path = explicit?.let(Path::of) ?: samples?.video?.takeIf(Files::isRegularFile)
            ?: config.get("generatedVideo", "").takeIf { it.isNotBlank() }?.let(Path::of)
            ?: error("请先点击「创建演示素材」并等待完成，或填写输入媒体路径")
        require(Files.isRegularFile(path)) { if (explicit == null) "演示视频不存在，请重新创建素材并等待完成" else "输入媒体不存在：$path" }
        return path
    }
    private fun workspace() = samples ?: ExampleSamples().also { samples = it }
    private fun <T> task(label: String, create: () -> RuntimeTask<T>, complete: (T) -> Unit = { report("$label：完成") }) {
        check(active == null) { "已有任务，请先取消或等待完成" }
        val generation = connection?.generation
        val submitted = create(); active = submitted; report("$label：${submitted.id}")
        submitted.result.whenComplete { value, error ->
            synchronized(this) { if (active === submitted) active = null }
            if (stopped || connection?.generation != generation) { (value as? AutoCloseable)?.close(); return@whenComplete }
            if (error != null) failure(error) else try { complete(value) } catch (failure: Throwable) { this.failure(failure) }
        }
    }
    fun generate() = dispatch {
        val sample = workspace()
        task("创建演示素材", { runtime.processing.execute(sample.videoArguments()) }) {
            synchronized(config) { config.set("generatedVideo", sample.video.toString()); config.set("generatedAudio", sample.audio.toString()); config.save() }
            report("演示视频：${sample.video}；标签音频：${sample.audio}；可读取媒体信息、转码、播放或读取标签")
        }
    }
    fun probe() = dispatch { task("探测", { runtime.processing.probe(input()) }) { info -> report("${info.format}；时长=${info.durationSeconds}；流=${info.streams.map { it.type + "/" + it.codec }}") } }
    fun transcode() = dispatch {
        val destination = workspace().output("mp4")
        task("转码", { runtime.processing.transcode(TranscodeOptions(input().toString(), destination.toString(), videoCodec = "mpeg4", extraArguments = listOf("-an"))) }) { report("转码完成：$destination") }
    }
    fun slowTranscode() = dispatch {
        val destination = workspace().output("mp4")
        task("可取消的实时转码", { runtime.processing.execute(listOf("-re", "-stream_loop", "-1", "-i", input().toString(), "-t", "120", "-an", "-c:v", "mpeg4", destination.toString())) }) { report("实时转码完成：$destination") }
    }
    // cancel() may synchronously let result complete; only that callback reports the outcome.
    fun cancel() = dispatch { val submitted = active; if (submitted == null) report("没有可取消的任务") else submitted.cancel() }
    fun play() = dispatch {
        closePlayer()
        val path = input()
        task("创建静音播放器", { runtime.playback.open(PlayerOptions(frameWidth = 640, frameHeight = 360, options = mapOf("audio" to "no", "loop-file" to "inf"))) }) { created ->
            synchronized(this) { if (stopped) { created.close(); return@task }; player = created }
            created.load(path.toString()).whenComplete { _, error -> if (error != null) { created.close(); failure(error) } else report("共享帧播放已开始；默认无窗口、无声音，可取帧或主动预览") }
        }
    }
    fun frames() = dispatch {
        requireNotNull(player) { "请先点击「开始静音播放」" }.frames().use { buffer ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var frame: MediaBuffer? = null
            while (frame == null && System.nanoTime() < deadline) { frame = buffer.latest(); if (frame == null) Thread.sleep(20) }
            val received = requireNotNull(frame) { "尚未收到视频帧" }
            check(received.bytes.size == received.width * received.height * 4)
            report("已收到帧：${received.width}×${received.height}；序号=${received.sequence}；字节=${received.bytes.size}")
        }
    }
    fun preview() = dispatch {
        if (preview != null) { report("预览已打开，请先关闭现有预览"); return@dispatch }
        val producer = requireNotNull(player) { "请先点击「开始静音播放」" }
        val frames = producer.frames()
        val view = ExamplePreview(frames) {
            synchronized(this) { preview = null; if (player === producer) player = null }
            runCatching { producer.close() }
        }
        synchronized(this) { if (stopped) view.close() else { preview = view; view.show() } }
    }
    fun metadata() = dispatch {
        config.reload()
        val path = if (config.get("input", "").isNotBlank()) input() else samples?.audio?.takeIf(Files::isRegularFile)
            ?: config.get("generatedAudio", "").takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::isRegularFile)
            ?: error("请先点击「创建演示素材」并等待完成，或填写受支持的音频路径")
        task("读取标签", { runtime.metadata.read(path) }) { report("标签=${it.fields}；封面=${it.artworkCount}") }
    }
    fun audio() = dispatch {
        val existing = capture
        if (existing != null) existing.features().whenComplete { features, error -> if (error != null) failure(error) else report("采样率=${features.sampleRate}；RMS=${features.rms}；峰值=${features.peak}") }
        else task("系统回环采集", { runtime.audio.capture() }) { created -> synchronized(this) { if (stopped) created.close() else capture = created }; report("采集已创建；再次点击读取分析。无音频时可能返回 CAPTURE_NOT_READY。") }
    }
    fun web() = dispatch {
        browser?.close(); browser = null
        task("打开空白网页", { runtime.web.open(WebOptions(profile = "example-smoke")) }) { created -> synchronized(this) { if (stopped) created.close() else browser = created }; report("空白 WebView2 已打开；关闭会话保留 Profile") }
    }
    fun render() = dispatch {
        val raw = workspace().rawFrame()
        task("创建渲染目标", { runtime.rendering.createTarget(2, 2) }) { target ->
            target.present(raw).whenComplete { _, error ->
                try {
                    if (error != null) failure(error)
                    else target.frames().use { report("渲染帧已发布：${it.latest()?.bytes?.size} 字节") }
                } catch (failure: Throwable) { this.failure(failure) } finally { target.close() }
            }
        }
    }
    private fun closePlayer() { preview?.close(); preview = null; player?.close(); player = null }
    private fun closeOwnedSessions() {
        var failure: Throwable? = null
        listOf<() -> Unit>({ closePlayer() }, { browser?.close(); browser = null }, { capture?.close(); capture = null }).forEach { close ->
            try { close() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
    fun closeSessions() = dispatch { closeOwnedSessions(); report("本示例的会话已关闭") }
    fun directProbe(path: Path) = executor.submit<Int> {
        val lease = runtime.tools.acquire("ffprobe")
        var process: Process? = null
        try {
            process = synchronized(this) {
                check(!stopped) { "示例已停用" }
                ProcessBuilder(lease.path.toString(), "-v", "error", "-show_format", path.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().also(directProcesses::add)
            }
            process.waitFor()
        } finally {
            process?.let { terminateDirect(it); directProcesses.remove(it) }
            // If termination throws, keep the lease registered: recovery must abandon its pin conservatively.
            lease.close()
        }
    }
    private fun terminateDirect(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); check(process.waitFor(2, TimeUnit.SECONDS)) { "自有进程未退出，保留工具租约" } }
    }
    override fun close() {
        val view = synchronized(this) { if (stopped) return; stopped = true; preview.also { preview = null } }
        view?.close()
        runCatching { active?.cancel() }
        directProcesses.forEach { runCatching { terminateDirect(it) } }
        runCatching { player?.close() }; runCatching { browser?.close() }; runCatching { capture?.close() }
        try { connection?.close(); connection = null } finally { executor.shutdownNow() }
        samples?.close()
    }
}

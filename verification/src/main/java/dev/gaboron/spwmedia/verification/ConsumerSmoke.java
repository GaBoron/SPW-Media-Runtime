package dev.gaboron.spwmedia.verification;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.gaboron.spwmedia.verification.Calls.*;

/** Optional native regression runner. No preview, browser window, system capture or sound is started. */
public final class ConsumerSmoke {
    public static void main(String[] args) throws Exception {
        Path distributions = Path.of(args[0]);
        String version = args[1];
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        require(temporary.getFileName().toString().startsWith("consumer-smoke-"), "A dedicated verification temporary root is required");
        boolean completed = false;
        Path invalid = Files.writeString(temporary.resolve("invalid.mp4"), "invalid media");
        try (var runtime = new PackagedRuntime(distributions.resolve("spw-media-runtime-" + version + ".spmod"));
             var first = new ConsumerPlugin(distributions.resolve("spw-media-example-" + version + ".spmod"), "smoke.example", "examples.ExampleOperations", runtime);
             var second = new ConsumerPlugin(distributions.resolve("spw-media-parallel-example-" + version + ".spmod"), "smoke.parallel", "parallel.ParallelOperations", runtime)) {
            first.invoke("connect"); second.invoke("connect");
            first.await(s -> s.text().contains("等待 Runtime")); second.await(s -> s.text().contains("等待 Runtime"));
            runtime.start();
            first.await(s -> s.text().contains("已连接") && !s.error()); second.await(s -> s.text().contains("已连接") && !s.error());
            runtime.assertQueryHasNoCopies();
            System.out.println("PASS consumer-first startup, independent SDKs and query zero-copy");
            for (var consumer : List.of(first, second)) {
                consumer.invoke("probe");
                consumer.await(s -> s.error() && s.text().contains("先点击生成"));
                consumer.success("generate", value -> value.startsWith("测试视频："));
                consumer.success("probe", consumer == first ? "时长=" : "格式=");
                consumer.input(temporary.resolve("missing.mp4").toString());
                consumer.invoke("probe"); consumer.await(s -> s.error() && s.text().contains("不存在"));
                consumer.input(invalid.toString());
                consumer.invoke("probe"); consumer.await(s -> s.error() && s.text().contains("MEDIA_PROCESS_FAILED"));
                consumer.input("");
            }
            first.success("transcode", "转码完成");
            first.success("metadata", "SPW Media Example");
            first.directProbe();
            if (System.getProperty("os.name").startsWith("Windows")) first.success("render", "16 字节");
            System.out.println("PASS real example generation, probe, transcode, tags, rendering and error classification");
            second.invoke("transcode"); second.await(s -> s.text().contains("实时转码，可取消："));
            second.invoke("cancel"); second.await(s -> s.text().contains("CANCELLED"));
            Thread.sleep(400);
            require(second.status().text().contains("CANCELLED"), "Late completion overwrote cancellation");
            first.success("play", "共享帧播放已开始"); second.success("play", "共享帧播放已启动");
            first.success("frames", "已收到帧"); second.success("frames", "视频帧序号=");
            first.invoke("slowTranscode"); first.await(s -> s.text().contains("可取消的实时转码："));
            first.disable();
            second.success("frames", "视频帧序号="); second.success("probe", "格式=");
            System.out.println("PASS cancel, repeat close, actual silent players and one-sided disable isolation");
            runtime.stop();
            second.invoke("probe"); second.await(s -> s.error() && s.text().contains("RUNTIME_UNAVAILABLE"));
            runtime.start();
            second.success("connect", "已连接");
            first.recreate(); first.success("connect", "已连接");
            first.success("transcode", "转码完成"); first.success("metadata", "SPW Media Example");
            second.success("probe", "格式=");
            second.success("play", "共享帧播放已启动"); second.success("frames", "视频帧序号=");
            second.success("closeSessions", "会话已关闭");
            System.out.println("PASS runtime restart, explicit reconnect and persisted generated media after consumer restart");
            completed = true;
        } finally {
            try { released(completed); } finally { recycle(temporary); }
        }
    }
    private static void released(boolean verify) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((!managedChildren().isEmpty() || !mediaThreads().isEmpty()) && System.nanoTime() < deadline) Thread.sleep(20);
        if (verify) {
            require(managedChildren().isEmpty(), "Managed backend process survived consumer/runtime shutdown: " + managedChildren());
            require(mediaThreads().isEmpty(), "Media executor survived shutdown: " + mediaThreads());
            System.out.println("PASS backend processes and consumer/SDK/runtime executors released");
        }
    }
    private static List<String> managedChildren() {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        // The Gradle-launched verification JVM can have its own Windows conhost companion.
        // Runtime executables (including recycling helpers) are loaded from the extracted package.
        try (var children = ProcessHandle.current().descendants()) {
            return children.filter(ProcessHandle::isAlive).filter(p -> p.info().command().map(command -> Path.of(command).toAbsolutePath().normalize().startsWith(temporary)).orElse(true))
                    .map(p -> p.pid() + " " + p.info().command().orElse("unknown")).toList();
        }
    }
    private static List<String> mediaThreads() {
        Set<String> names = Set.of("Media example actions", "Parallel media example", "SPW Media SDK", "SPW Media task", "SPW Media deadline");
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName).filter(names::contains).toList();
    }
    private static void recycle(Path target) throws Exception {
        boolean recycled = false;
        try {
            List<String> command = System.getProperty("os.name").startsWith("Windows")
                    ? List.of(System.getProperty("spw.media.recycle.helper"), target.toString()) : List.of("gio", "trash", "--", target.toString());
            Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            recycled = process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && !Files.exists(target);
        } catch (java.io.IOException | SecurityException error) { /* retain when system trash is unavailable */ }
        if (!recycled) System.err.println("Recycle unavailable; retained verification directory: " + target);
    }
    private ConsumerSmoke() { }
}

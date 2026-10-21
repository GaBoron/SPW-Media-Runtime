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
        Path fixture = Path.of(args[2]);
        try (var runtime = new PackagedRuntime(distributions.resolve("spw-media-runtime-" + version + ".spmod"));
             var first = new ConsumerPlugin(fixture, "smoke.first", runtime);
             var second = new ConsumerPlugin(fixture, "smoke.second", runtime)) {
            require(first.sdkType() != second.sdkType(), "Consumers share SDK class identity");
            require("WAITING".equals(first.state().get("status")), "Expected consumer-first startup");
            runtime.start(); first.connected(1); second.connected(1);
            runtime.assertQueryHasNoCopies();
            System.out.println("PASS consumer-first startup, independent SDK classloaders and query zero-copy");
            for (var consumer : List.of(first, second)) {
                require(Integer.valueOf(0).equals(consumer.invoke("generate")), "Generation failed");
                var info = consumer.values("probe", "");
                require(((Number)info.get("duration")).doubleValue()>0 && ((Number)info.get("streams")).intValue()>0, "Invalid media info");
                consumer.failure("probe", "MEDIA_PROCESS_FAILED", temporary.resolve("missing.mp4").toString());
                consumer.failure("probe", "MEDIA_PROCESS_FAILED", invalid.toString());
            }
            require(Integer.valueOf(0).equals(first.invoke("transcode")), "Transcode failed");
            require("Fixture".equals(first.invoke("metadata")), "Tag read failed");
            require(Integer.valueOf(0).equals(first.invoke("directProbe")), "Leased tool failed");
            if (System.getProperty("os.name").startsWith("Windows")) require(Integer.valueOf(16).equals(first.invoke("render")), "Rendering failed");
            System.out.println("PASS managed generation, typed probe/transcode, tags, rendering, leased tool and structured errors");
            for (var consumer : List.of(first, second)) {
                consumer.invoke("startSlow");
                require("CANCELLED".equals(consumer.invoke("cancel")), "Cancellation did not reach its terminal result");
                consumer.invoke("play");
                var frame = consumer.values("frame");
                require(((Number)frame.get("bytes")).intValue()==320*180*4 && ((Number)frame.get("sequence")).longValue()>0, "Invalid frame");
            }
            first.invoke("startSlow"); first.disable();
            second.values("frame"); second.values("probe", "");
            System.out.println("PASS cancellation, repeat close, silent playback and one-sided disable isolation");
            runtime.stop(); runtime.start(); second.connected(2);
            require(Integer.valueOf(0).equals(second.invoke("transcode")), "Post-restart transcode failed");
            second.invoke("play"); second.values("frame"); second.invoke("closeSessions");
            System.out.println("PASS runtime restart, generation recovery and explicit media reconstruction");
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
        Set<String> names = Set.of("SPW Media connection", "SPW Media cleanup", "SPW Media SDK", "SPW Media task", "SPW Media deadline");
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

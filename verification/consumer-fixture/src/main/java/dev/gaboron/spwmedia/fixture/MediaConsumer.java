package dev.gaboron.spwmedia.fixture;

import dev.gaboron.spwmedia.protocol.*;
import dev.gaboron.spwmedia.sdk.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Public SDK consumer. Only JDK values cross its isolated classloader boundary. No host/UI dependency. */
public final class MediaConsumer implements AutoCloseable {
    private final Path directory;
    private final RuntimeConnection connection;
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private Player player;
    private SharedBuffer frames;
    private RuntimeTask<ProcessResult> pending;
    public MediaConsumer(String pluginId, Path directory) {
        this.directory = directory;
        connection = MediaRuntime.connectWhenAvailable(pluginId, new ConnectionOptions(30_000, 100, 100, 5_000), client -> {
            try { closeSessions(); stopProcesses(); return CompletableFuture.completedFuture(null); }
            catch (Exception error) { return CompletableFuture.failedFuture(error); }
        });
    }
    private MediaRuntime client() { return Objects.requireNonNull(connection.getCurrent(), "Runtime unavailable"); }
    private Path video() { return directory.resolve("sample.mp4"); }
    private static <T> T await(CompletionStage<T> result) throws Exception { return result.toCompletableFuture().get(25, TimeUnit.SECONDS); }
    private static <T> T await(RuntimeTask<T> task) throws Exception { return await(task.getResult()); }
    public Map<String, Object> connectionState() {
        var state = connection.getSnapshot();
        return Map.of("status", state.getStatus().name(), "generation", state.getGeneration(), "error", state.getError() == null ? "" : state.getError().getCode());
    }
    public Map<String, Object> probe(String input) throws Exception {
        MediaInfo info = await(client().getProcessing().probe(input.isEmpty() ? video() : Path.of(input)));
        return Map.of("format", info.getFormat(), "duration", Objects.requireNonNullElse(info.getDurationSeconds(), 0.0), "streams", info.getStreams().size());
    }
    public int generate() throws Exception {
        return await(client().getProcessing().execute(List.of("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono", "-map", "0:v", "-t", "2", "-an", "-c:v", "mpeg4", video().toString(), "-map", "1:a", "-t", "2", "-vn", "-c:a", "flac", "-metadata", "title=Fixture", directory.resolve("sample.flac").toString()), 30_000)).getExitCode();
    }
    public int transcode() throws Exception {
        return await(client().getProcessing().transcode(new TranscodeOptions(video().toString(), directory.resolve(UUID.randomUUID()+".mp4").toString(), "mpeg4", null, null, null, List.of("-an"), false), 30_000)).getExitCode();
    }
    public String startSlow() {
        pending = client().getProcessing().execute(List.of("-re", "-stream_loop", "-1", "-i", video().toString(), "-t", "120", "-an", "-c:v", "mpeg4", directory.resolve(UUID.randomUUID()+".mp4").toString()), 150_000);
        return pending.getId();
    }
    public String cancel() throws Exception {
        pending.cancel();
        try { await(pending); throw new AssertionError("Expected cancellation"); }
        catch (ExecutionException failure) { return Objects.requireNonNull(MediaRuntimeException.unwrap(failure)).getDetail().getCode(); }
    }
    public Map<String, Object> play() throws Exception {
        closeSessions();
        player = await(client().getPlayback().open(new PlayerOptions("no", 0, Map.of("mute", "yes", "volume", "0", "loop-file", "inf"), 320, 180)));
        await(player.load(video().toString()));
        frames = player.frames();
        return Map.of("player", player.getId());
    }
    public Map<String, Object> frame() throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        do {
            MediaBuffer frame = frames.latest();
            if (frame != null) return Map.of("sequence", frame.getSequence(), "bytes", frame.getBytes().length, "width", frame.getWidth(), "height", frame.getHeight());
            Thread.sleep(10);
        } while (System.nanoTime()<deadline);
        throw new AssertionError("No video frame");
    }
    public String metadata() throws Exception {
        return await(client().getMetadata().read(directory.resolve("sample.flac"))).getFields().get("TITLE").getFirst();
    }
    public int render() throws Exception {
        Path pixels = directory.resolve("frame.bgra");
        Files.write(pixels, new byte[16]);
        var target = await(client().getRendering().createTarget(2, 2, "BGRA"));
        try {
            await(target.present(pixels));
            try (var mapping = target.frames()) { return Objects.requireNonNull(mapping.latest()).getBytes().length; }
        } finally { target.close(); }
    }
    public int directProbe() throws Exception {
        var lease = client().getTools().acquire("ffprobe");
        try {
            Process process = new ProcessBuilder(lease.getPath().toString(), "-v", "error", "-show_format", video().toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            processes.add(process);
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) throw new TimeoutException("FFprobe");
                return process.exitValue();
            } finally { stopProcesses(); }
        } finally {
            if (processes.isEmpty()) lease.close();
        }
    }
    public void reconnect() throws Exception { await(connection.reconnect()); }
    public synchronized void closeSessions() {
        if (frames != null) { frames.close(); frames = null; }
        if (player != null) { player.close(); player = null; }
    }
    private void stopProcesses() throws Exception {
        for (Process process : processes) {
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IllegalStateException("Caller process did not exit"); } }
            processes.remove(process);
        }
    }
    @Override public void close() { connection.close(); }
}

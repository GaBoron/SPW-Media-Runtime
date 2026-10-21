package dev.gaboron.spwmedia.verification;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.gaboron.spwmedia.verification.Calls.*;

/** Loads a test-only consumer and its own public SDK, without Example UI or host configuration. */
final class ConsumerPlugin implements AutoCloseable {
    private final URLClassLoader loader;
    private final Object consumer;
    ConsumerPlugin(Path archive, String id, PackagedRuntime runtime) throws Exception {
        Path root = Files.createTempDirectory(id + "-");
        PackagedRuntime.unpack(archive, root);
        List<URL> urls = PackagedRuntime.jars(root.resolve("lib"));
        urls.add(root.resolve("classes").toUri().toURL());
        urls.add(jar("kotlin-stdlib-").toUri().toURL());
        loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        runtime.identify(loader, id);
        consumer = Class.forName("dev.gaboron.spwmedia.fixture.MediaConsumer", true, loader)
                .getConstructor(String.class, Path.class).newInstance(id, root);
    }
    Object invoke(String method, Object... args) throws Exception { return call(consumer, method, args); }
    Class<?> sdkType() throws Exception { return Class.forName("dev.gaboron.spwmedia.sdk.MediaRuntime", false, loader); }
    @SuppressWarnings("unchecked") Map<String, Object> state() throws Exception { return (Map<String, Object>)invoke("connectionState"); }
    @SuppressWarnings("unchecked") Map<String, Object> values(String method, Object... args) throws Exception { return (Map<String, Object>)invoke(method, args); }
    void connected(long generation) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
        do {
            var state = state();
            if ("CONNECTED".equals(state.get("status")) && ((Number)state.get("generation")).longValue() >= generation) return;
            Thread.sleep(20);
        } while (System.nanoTime()<deadline);
        throw new AssertionError("Consumer did not connect: " + state());
    }
    void failure(String method, String code, Object... args) throws Exception {
        try { invoke(method, args); throw new AssertionError("Expected " + code); }
        catch (Exception error) {
            Throwable cause = error;
            while ((cause instanceof java.util.concurrent.ExecutionException || cause instanceof java.util.concurrent.CompletionException) && cause.getCause()!=null) cause = cause.getCause();
            require(cause.getClass().getName().equals("dev.gaboron.spwmedia.sdk.MediaRuntimeException"), "Unstructured failure: " + cause);
            require(code.equals(call(call(cause, "getDetail"), "getCode")), "Unexpected error: " + cause);
        }
    }
    void disable() throws Exception { ((AutoCloseable)consumer).close(); ((AutoCloseable)consumer).close(); }
    @Override public void close() throws Exception { try { disable(); } finally { loader.close(); } }
}
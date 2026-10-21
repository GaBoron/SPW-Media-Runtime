package dev.gaboron.spwmedia.verification;

import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import static dev.gaboron.spwmedia.verification.Calls.*;

/** Invokes the real example controller; only the host-owned configuration store is substituted. */
final class ConsumerPlugin implements AutoCloseable {
    record Status(long revision, String text, boolean error) { }
    private final URLClassLoader loader;
    private final Class<?> operationsType;
    private final Object configuration;
    private final String id;
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private Object operations;
    private Object lastStatus;
    private long revision;
    ConsumerPlugin(Path archive, String id, String namespace, PackagedRuntime runtime) throws Exception {
        this.id = id;
        Path root = Files.createTempDirectory(id + "-");
        PackagedRuntime.unpack(archive, root);
        List<URL> urls = PackagedRuntime.jars(root.resolve("lib"));
        urls.add(root.resolve("classes").toUri().toURL());
        urls.add(jar("kotlin-stdlib-").toUri().toURL());
        urls.add(jar("spw-workshop-api-").toUri().toURL());
        loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        runtime.identify(loader, id);
        Class<?> configType = Class.forName("com.xuncorp.spw.workshop.api.config.ConfigHelper", true, loader);
        configuration = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{configType}, (proxy, method, args) -> switch (method.getName()) {
            case "get" -> values.getOrDefault((String)args[0], args[1]);
            case "set" -> { values.put((String)args[0], args[1]); yield null; }
            case "save", "reload" -> true;
            case "getConfigPath" -> root.resolve("config.json");
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "In-memory host configuration";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        operationsType = Class.forName("dev.gaboron.spwmedia." + namespace, true, loader);
        operations = operationsType.getConstructor(String.class, configType).newInstance(id, configuration);
    }
    void invoke(String method) throws Exception { call(operations, method); }
    Status status() throws Exception {
        Object value = call(operations, "getResult");
        if (value != lastStatus) { lastStatus = value; revision++; }
        return new Status(revision, (String)call(value, "getText"), (Boolean)call(value, "isError"));
    }
    Status await(Predicate<Status> expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        Status state;
        do { state = status(); if (expected.test(state)) return state; Thread.sleep(20); } while (System.nanoTime() < deadline);
        throw new AssertionError(id + ": unexpected status: " + state);
    }
    void success(String operation, String text) throws Exception {
        success(operation, value -> value.contains(text));
    }
    void success(String operation, Predicate<String> expected) throws Exception {
        long before = status().revision();
        invoke(operation);
        Status state = await(s -> s.revision() > before && (expected.test(s.text()) || s.error()));
        require(!state.error(), id + " " + operation + ": " + state.text());
    }
    void input(String path) { values.put("input", path); }
    void directProbe() throws Exception {
        var future = (java.util.concurrent.Future<?>)call(operations, "directProbe", Path.of((String)values.get("generatedVideo")));
        require(Integer.valueOf(0).equals(future.get(10, java.util.concurrent.TimeUnit.SECONDS)), "Leased FFprobe failed");
    }
    void recreate() throws Exception {
        ((AutoCloseable)operations).close();
        operations = operationsType.getConstructors()[0].newInstance(id, configuration);
    }
    void disable() throws Exception { ((AutoCloseable)operations).close(); ((AutoCloseable)operations).close(); }
    @Override public void close() throws Exception { try { disable(); } finally { loader.close(); } }
}

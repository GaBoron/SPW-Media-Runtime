package dev.gaboron.spwmedia.verification;

import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.ZipFile;
import static dev.gaboron.spwmedia.verification.Calls.*;

/** Runs the actual delivery JARs and native files in an isolated JVM-local endpoint. */
final class PackagedRuntime implements AutoCloseable {
    private final Path root;
    private final URLClassLoader loader;
    private final List<URL> dependencies;
    private final Map<ClassLoader, String> owners = new ConcurrentHashMap<>();
    private Object endpoint;
    PackagedRuntime(Path archive) throws Exception {
        root = Files.createTempDirectory("rt-");
        unpack(archive, root);
        dependencies = jars(root.resolve("lib"));
        dependencies.add(jar("kotlin-stdlib-").toUri().toURL());
        loader = new URLClassLoader(dependencies.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }
    static void unpack(Path archive, Path target) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (var entry : zip.stream().toList()) {
                Path output = target.resolve(entry.getName()).normalize();
                require(output.startsWith(target), "Package path escapes fixture");
                if (entry.isDirectory()) Files.createDirectories(output);
                else { Files.createDirectories(output.getParent()); try (var input = zip.getInputStream(entry)) { Files.copy(input, output); } }
            }
        }
    }
    static List<URL> jars(Path path) throws Exception {
        List<URL> result = new ArrayList<>();
        try (var entries = Files.list(path)) { for (Path jar : entries.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) result.add(jar.toUri().toURL()); }
        return result;
    }
    void identify(ClassLoader loader, String id) { owners.put(loader, id); }
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private Object function(String name, Function<Object[], Object> action) throws Exception {
        return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{type("kotlin.jvm.functions." + name)}, (proxy, method, args) -> switch (method.getName()) {
            case "invoke" -> action.apply(args == null ? new Object[0] : args);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Verification callback";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    void start() throws Exception {
        require(endpoint == null, "Runtime is already started");
        Path storage = root.resolve("data");
        Object assets = type("dev.gaboron.spwmedia.core.NativeAssets").getConstructor(Path.class, Path.class).newInstance(root.resolve("native"), storage.resolve("native/v"));
        List<Object> providers = new ArrayList<>();
        for (String name : List.of("processing.Processing", "playback.Playback", "web.Web", "metadata.Metadata", "audio.Audio", "rendering.Rendering")) providers.add(type("dev.gaboron.spwmedia." + name + "Provider").getConstructor().newInstance());
        String classpath = String.join(java.io.File.pathSeparator, dependencies.stream().map(url -> { try { return Path.of(url.toURI()).toString(); } catch (Exception e) { throw new RuntimeException(e); } }).toList());
        Object commandFactory = function("Function1", args -> {
            try {
                if (!"playback".equals(args[0])) return null;
                Object commands = type("dev.gaboron.spwmedia.core.WorkerCommands").getField("INSTANCE").get(null);
                return call(commands, "playback", assets, classpath, Path.of(System.getProperty("spw.smoke.javaHome", System.getProperty("java.home"))));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        Class<?> engineType = type("dev.gaboron.spwmedia.core.RuntimeEngine");
        Object engine = engineType.getConstructor(List.class, type("dev.gaboron.spwmedia.provider.Assets"), Path.class, Map.class, int.class, type("kotlin.jvm.functions.Function1")).newInstance(providers, assets, storage, Map.of(), 4, commandFactory);
        var walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        Object identity = function("Function0", ignored -> walker.walk(frames -> frames.map(f -> f.getDeclaringClass().getClassLoader()).filter(Objects::nonNull).map(owners::get).filter(Objects::nonNull).findFirst().orElse(null)));
        endpoint = type("dev.gaboron.spwmedia.core.RuntimeEndpoint").getConstructor(engineType, type("kotlin.jvm.functions.Function0")).newInstance(engine, identity);
    }
    void assertQueryHasNoCopies() { require(!Files.exists(root.resolve("data/native")), "Consumer startup prepared native cache"); }
    void stop() throws Exception { if (endpoint != null) { ((AutoCloseable)endpoint).close(); endpoint = null; } }
    @Override public void close() throws Exception { try { stop(); } finally { loader.close(); } }
}

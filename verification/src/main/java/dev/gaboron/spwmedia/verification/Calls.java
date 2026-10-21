package dev.gaboron.spwmedia.verification;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Reflection keeps the verification runner outside every packaged plugin/SDK classloader. */
final class Calls {
    static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try { return method.invoke(target, args); }
            catch (IllegalArgumentException ignored) { }
            catch (InvocationTargetException error) { throw cause(error); }
        }
        throw new NoSuchMethodException(name);
    }
    static Exception cause(InvocationTargetException error) {
        return error.getCause() instanceof Exception exception ? exception : new RuntimeException(error.getCause());
    }
    static Path jar(String prefix) {
        return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(Path::of).filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".jar"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing verification dependency: " + prefix));
    }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private Calls() { }
}

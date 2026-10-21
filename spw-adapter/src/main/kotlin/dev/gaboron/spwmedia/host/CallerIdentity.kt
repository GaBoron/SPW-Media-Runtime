package dev.gaboron.spwmedia.host

import org.pf4j.PluginClassLoader
import java.nio.file.*
import java.util.WeakHashMap
import java.util.jar.JarFile
import java.util.jar.Manifest

/** Resolve plugin identity from PF4J's public classloader URLs and its own manifest, without private reflection. */
internal class CallerIdentity(private val runtimeLoader: ClassLoader) {
    private val identifiers = WeakHashMap<PluginClassLoader, String>()
    fun current(): String? {
        val caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
            frames.map { it.declaringClass.classLoader }
                .filter { it is PluginClassLoader && it !== runtimeLoader }
                .findFirst().orElse(null)
        } as? PluginClassLoader ?: return null
        if (caller.isClosed) return null
        return synchronized(identifiers) { identifiers[caller] ?: descriptor(caller)?.also { identifiers[caller] = it } }
    }
    private fun descriptor(loader: PluginClassLoader): String? = loader.getURLs().firstNotNullOfOrNull { url ->
        if (url.protocol != "file") return@firstNotNullOfOrNull null
        runCatching {
            val path = Path.of(url.toURI())
            if (Files.isDirectory(path)) {
                val manifest = path.resolve("META-INF/MANIFEST.MF")
                if (Files.isRegularFile(manifest)) Files.newInputStream(manifest).use { Manifest(it).mainAttributes.getValue("Plugin-Id") } else null
            } else if (path.toString().endsWith(".jar")) JarFile(path.toFile()).use { it.manifest?.mainAttributes?.getValue("Plugin-Id") }
            else null
        }.getOrNull()
    }
}

import java.util.jar.Manifest
import java.util.zip.ZipFile

// Validate the delivered plugin boundary, including the independent Maven consumer.
tasks.register("checkPackages") {
    dependsOn("plugin", "examplePlugin", "parallelExamplePlugin")
    doLast {
        val distributions = layout.buildDirectory.dir("distributions").get().asFile
        val plugins = mapOf(
            "spw-media-runtime" to "dev/gaboron/spwmedia/host/MediaRuntimePlugin.class",
            "spw-media-example" to "dev/gaboron/spwmedia/examples/MediaExamplePlugin.class",
            "spw-media-parallel-example" to "dev/gaboron/spwmedia/parallel/ParallelPlugin.class"
        )
        for ((id, entryPoint) in plugins) ZipFile(distributions.resolve("$id-${project.version}.spmod")).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toSet()
            check("classes/$entryPoint" in names) { "$id: missing entry point" }
            val manifest = archive.getInputStream(archive.getEntry("classes/META-INF/MANIFEST.MF")).use(::Manifest).mainAttributes
            check(manifest.getValue("Plugin-Id") == id && manifest.getValue("Plugin-Version") == project.version.toString()) { "$id: inconsistent plugin manifest" }
            check(names.none { name -> name.startsWith("lib/kotlin-stdlib-") || name.startsWith("lib/skiko-") || name.startsWith("lib/compose-") }) { "$id: duplicated host dependencies" }
            if (id == "spw-media-runtime") {
                for (path in listOf("windows-x64/spw-jvm-worker.exe", "linux-x64/bin/spw-jvm-worker", "windows-x64/asset-manifest.properties", "linux-x64/asset-manifest.properties")) {
                    check("native/$path" in names) { "$id: missing $path" }
                }
            } else {
                check(manifest.getValue("Plugin-Has-Config") == "true" && "classes/preference_config.json" in names) { "$id: missing configuration entry" }
                check("lib/spw-media-sdk-${project.version}.jar" in names && "lib/spw-media-protocol-${project.version}.jar" in names) { "$id: missing independent SDK copy" }
                check(names.none { it.startsWith("native/") || it.startsWith("lib/spw-media-runtime-core-") }) { "$id: bundled Runtime implementation" }
                val configuration = archive.getInputStream(archive.getEntry("classes/preference_config.json")).bufferedReader().use { it.readText() }
                check(".reconnect\"" in configuration) { "$id: missing reconnect action" }
            }
            println("$id: package boundary verified")
        }
    }
}
tasks.named("delivery") { dependsOn("checkPackages") }

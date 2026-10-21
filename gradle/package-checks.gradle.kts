import java.util.jar.Manifest
import java.util.zip.ZipFile

// Runtime packaging is independent of teaching examples and test consumers.
tasks.register("checkPackages") {
    dependsOn("plugin")
    doLast {
        val archive = layout.buildDirectory.file("distributions/spw-media-runtime-${project.version}.spmod").get().asFile
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            check("classes/dev/gaboron/spwmedia/host/MediaRuntimePlugin.class" in names)
            val manifest = zip.getInputStream(zip.getEntry("classes/META-INF/MANIFEST.MF")).use(::Manifest).mainAttributes
            check(manifest.getValue("Plugin-Id") == "spw-media-runtime" && manifest.getValue("Plugin-Version") == project.version.toString())
            check(names.none { it.startsWith("lib/kotlin-stdlib-") || it.startsWith("lib/annotations-") || it.startsWith("lib/spw-workshop-api-") || it.startsWith("lib/pf4j-") || it.startsWith("lib/skiko-") || it.startsWith("lib/compose-") }) { "Duplicated host dependencies" }
            for (path in listOf("windows-x64/spw-jvm-worker.exe", "linux-x64/bin/spw-jvm-worker", "windows-x64/asset-manifest.properties", "linux-x64/asset-manifest.properties")) check("native/$path" in names) { "Missing $path" }
        }
    }
}
tasks.named("delivery") { dependsOn("checkPackages") }
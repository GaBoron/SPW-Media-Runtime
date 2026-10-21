import java.util.zip.ZipFile
import org.gradle.plugins.signing.Sign

val sdkModules = listOf("protocol", "sdk", "sdk-compose", "provider-api")
tasks.register("checkSdkArtifacts") {
    group = "verification"
    dependsOn("sdkBundle", "checkCentralMetadata")
    dependsOn(sdkModules.map { ":$it:check" }, ":runtime-core:test")
    doLast {
        val repository = layout.buildDirectory.dir("maven/${project.group.toString().replace('.', '/')}").get().asFile
        for (module in sdkModules) {
            val artifact = "spw-media-$module"
            val directory = repository.resolve("$artifact/${project.version}")
            for (suffix in listOf(".jar", "-sources.jar", "-javadoc.jar")) {
                val file = directory.resolve("$artifact-${project.version}$suffix")
                check(file.isFile && file.length() > 0) { "Missing publication artifact: $file" }
                ZipFile(file).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toList()
                    check(names.none { it.endsWith(".dll") || it.endsWith(".so") || it.endsWith(".exe") || it.startsWith("native/") || it.startsWith("dev/gaboron/spwmedia/core/") || it.startsWith("dev/gaboron/spwmedia/worker/") || it.startsWith("dev/gaboron/spwmedia/host/") }) { "$artifact includes a Runtime implementation" }
                    if (suffix == "-sources.jar") check(names.any { it.endsWith(".kt") }) { "$artifact has no actual source" }
                    if (suffix == "-javadoc.jar") check(names.any { it.endsWith(".html") }) { "$artifact has no generated reference" }
                }
            }
        }
        ZipFile(layout.buildDirectory.file("distributions/spw-media-sdk-${project.version}.zip").get().asFile).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            val prefix = project.group.toString().replace('.', '/') + "/"
            check(names.all { it.startsWith(prefix) && sdkModules.any { module -> it.startsWith("${prefix}spw-media-$module/${project.version}/") || it.startsWith("${prefix}spw-media-$module/maven-metadata.xml") } }) { "SDK archive includes unrelated versions or components" }
        }
        logger.lifecycle("Four public modules: POM, real Sources/Javadoc and SDK-only archive verified")
    }
}
tasks.register("checkSignedSdkArtifacts") {
    group = "verification"
    dependsOn("checkSdkArtifacts")
    dependsOn(sdkModules.map { ":$it:signLibraryPublication" })
    doLast {
        check(providers.gradleProperty("centralRelease").orNull == "true") { "Signing checks require -PcentralRelease=true" }
        sdkModules.forEach { module ->
            val signing = project(":$module").tasks.named<Sign>("signLibraryPublication").get()
            check(signing.signatures.size >= 4) { "$module: incomplete publication signature set" }
            signing.signatures.forEach { signature ->
                check(signature.file.isFile && signature.file.readText().startsWith("-----BEGIN PGP SIGNATURE-----")) { "$module: missing PGP signature" }
            }
        }
    }
}
// No signing or publishing credentials are involved in normal delivery checks.
tasks.named("delivery") { dependsOn("checkSdkArtifacts") }

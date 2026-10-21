import java.util.zip.ZipFile

plugins { java }
val sdkVersion = providers.gradleProperty("sdkVersion").get()
val sdkGroup = providers.gradleProperty("sdkGroup").get()
version = sdkVersion
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
repositories {
    providers.gradleProperty("sdkRepository").orNull?.let { maven { url = uri(it) } }
    mavenCentral()
}
dependencies {
    implementation("$sdkGroup:spw-media-sdk:$sdkVersion")
    // A host library is required to compile Java against Kotlin declarations, but never packaged.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}
val publicArtifacts = configurations.create("publicArtifacts") {
    isCanBeConsumed = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
}
listOf("sdk", "protocol", "sdk-compose", "provider-api").forEach {
    dependencies.add(publicArtifacts.name, "$sdkGroup:spw-media-$it:$sdkVersion")
}
tasks.register("checkPublicModules") {
    doLast {
        val artifacts = publicArtifacts.resolvedConfiguration.resolvedArtifacts
        val hostArtifacts = artifacts.filter {
            val id = it.moduleVersion.id
            id.group == "org.jetbrains.kotlin" || (id.group == "org.jetbrains" && id.name == "annotations") ||
                id.group.startsWith("org.jetbrains.compose") || id.group.startsWith("androidx.compose") ||
                id.group == "org.jetbrains.skiko" || id.group == "org.jetbrains.skia" ||
                id.group == "org.pf4j" || id.group == "com.github.Moriafly.spw-workshop-api"
        }
        check(hostArtifacts.isEmpty()) { "Public SDK transitively includes host libraries: ${hostArtifacts.map { it.moduleVersion.id }}" }
        val files = artifacts.map { it.file.name }
        listOf("sdk", "protocol", "sdk-compose", "provider-api").forEach { check("spw-media-$it-$sdkVersion.jar" in files) }
        check(files.none { it.startsWith("kotlin-stdlib-") || it.startsWith("annotations-") || it.contains("runtime-core") || it.contains("native-worker") })
        println("All four public Maven modules resolved without host or Runtime dependencies")
    }
}
tasks.register<Zip>("fixture") {
    dependsOn(tasks.classes)
    archiveFileName.set("consumer-fixture-$sdkVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("fixture"))
    into("classes") { from(sourceSets.main.get().output) }
    into("lib") { from(configurations.runtimeClasspath) }
    doLast {
        ZipFile(archiveFile.get().asFile).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toList()
            check(names.none { it.startsWith("lib/kotlin-stdlib-") || it.startsWith("lib/annotations-") || it.contains("runtime-core") }) { "Consumer bundled host or implementation dependencies" }
        }
    }
}

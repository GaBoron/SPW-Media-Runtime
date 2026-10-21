import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}
val sdkVersion = providers.gradleProperty("sdkVersion").getOrElse("0.2.0")
val sdkGroup = providers.gradleProperty("sdkGroup").getOrElse("io.github.gaboron.spwmedia")
group = sdkGroup
version = sdkVersion
kotlin { jvmToolchain(25) }
repositories {
    providers.gradleProperty("sdkRepository").orNull?.let { maven { url = uri(it) } }
    mavenCentral()
    google()
    maven("https://jitpack.io")
}
dependencies {
    implementation("$sdkGroup:spw-media-sdk:$sdkVersion")
    implementation("$sdkGroup:spw-media-sdk-compose:$sdkVersion")
    compileOnly(kotlin("stdlib"))
    compileOnly("org.jetbrains.compose.desktop:desktop:1.12.1")
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
}
tasks.jar {
    manifest.attributes(mapOf("Plugin-Id" to "spw-media-example", "Plugin-Class" to "dev.gaboron.spwmedia.examples.MediaExamplePlugin", "Plugin-Version" to sdkVersion, "Plugin-Provider" to "GaBoron", "Plugin-Name" to "SPW Media Example", "Plugin-Has-Config" to "true"))
}
tasks.register<Zip>("plugin") {
    dependsOn(tasks.jar)
    archiveFileName.set("spw-media-example-$sdkVersion.spmod")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("classes") { from(tasks.jar.map { zipTree(it.archiveFile) }) }
    into("lib") {
        from(configurations.runtimeClasspath)
        exclude("kotlin-stdlib-*.jar", "annotations-*.jar")
    }
    from("../LICENSE")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doLast {
        val hostArtifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.filter {
            val id = it.moduleVersion.id
            id.group == "org.jetbrains.kotlin" || (id.group == "org.jetbrains" && id.name == "annotations") ||
                id.group.startsWith("org.jetbrains.compose") || id.group.startsWith("androidx.compose") ||
                id.group == "org.jetbrains.skiko" || id.group == "org.jetbrains.skia" ||
                id.group == "org.pf4j" || id.group == "com.github.Moriafly.spw-workshop-api"
        }
        check(hostArtifacts.isEmpty()) { "Plugin runtime includes host libraries: ${hostArtifacts.map { it.moduleVersion.id }}" }
        ZipFile(archiveFile.get().asFile).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toList()
            check(names.none { it.startsWith("native/") || it.contains("runtime-core") || it.startsWith("lib/kotlin-stdlib-") || it.startsWith("lib/annotations-") || it.startsWith("lib/skiko-") || it.startsWith("lib/compose-") })
        }
    }
}

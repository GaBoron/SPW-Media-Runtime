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

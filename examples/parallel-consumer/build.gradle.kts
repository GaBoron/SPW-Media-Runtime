plugins { kotlin("jvm") version "2.4.20" }
group = "dev.gaboron.spwmedia.examples"
version = providers.gradleProperty("sdkVersion").get()
kotlin { jvmToolchain(25) }
repositories {
    maven { url = uri(providers.gradleProperty("sdkRepository").get()) }
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    maven("https://jitpack.io")
}
dependencies {
    implementation("dev.gaboron.spwmedia:spw-media-sdk:$version")
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
}
tasks.jar {
    manifest.attributes(mapOf("Plugin-Id" to "spw-media-parallel-example", "Plugin-Class" to "dev.gaboron.spwmedia.parallel.ParallelPlugin", "Plugin-Version" to project.version, "Plugin-Provider" to "GaBoron", "Plugin-Name" to "SPW Media Parallel Example", "Plugin-Has-Config" to "true"))
}
tasks.register<Zip>("plugin") {
    dependsOn(tasks.jar)
    archiveFileName.set("spw-media-parallel-example-$version.spmod")
    destinationDirectory.set(file(providers.gradleProperty("outputDir").getOrElse(layout.buildDirectory.dir("distributions").get().asFile.absolutePath)))
    into("classes") { from(tasks.jar.map { zipTree(it.archiveFile) }) }
    into("lib") { from(configurations.runtimeClasspath); exclude("kotlin-stdlib-*.jar", "annotations-*.jar") }
    from("../../LICENSE")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

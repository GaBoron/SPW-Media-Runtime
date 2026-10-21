import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.1" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

val hostApi = "com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23"
subprojects {
    group = rootProject.group
    version = rootProject.version
    if (path == ":providers") return@subprojects
    if (path == ":verification") {
        apply(plugin = "java")
        extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
        dependencies {
            "runtimeOnly"(hostApi) { isTransitive = false }
            "runtimeOnly"("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
        }
        return@subprojects
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "java-library")
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(25) }
    extensions.configure<JavaPluginExtension> { withSourcesJar() }
    dependencies {
        "compileOnly"(kotlin("stdlib"))
        "implementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        "testImplementation"(kotlin("test-junit"))
    }
    tasks.withType<Jar>().configureEach {
        archiveBaseName.set("spw-media-${project.name}")
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    tasks.withType<Test>().configureEach {
        systemProperty("spw.media.recycle.helper", rootProject.file("build/native/windows-x64/spw-recycle.exe").absolutePath)
    }
    if (name in setOf("protocol", "sdk", "sdk-compose", "provider-api")) {
        apply(plugin = "maven-publish")
        apply(plugin = "org.jetbrains.dokka")
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("library") {
                    from(components["java"])
                    artifactId = "spw-media-${project.name}"
                    pom {
                        name.set("SPW Media ${project.name}")
                        description.set("Shared media runtime for SPW workshop plugins")
                        url.set("https://github.com/GaBoron/SPW-Media-Runtime")
                        licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
                    }
                }
            }
            repositories { maven { name = "delivery"; url = rootProject.layout.buildDirectory.dir("maven").get().asFile.toURI() } }
        }
    }
}
project(":sdk") { dependencies { "api"(project(":protocol")) } }
project(":sdk") { dependencies { "testImplementation"(project(":runtime-core")) } }
project(":protocol") {
    dependencies { "api"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
    val generated = layout.buildDirectory.dir("generated/version")
    val generateVersion = tasks.register("generateVersion") {
        inputs.property("version", project.version.toString())
        outputs.dir(generated)
        doLast {
            generated.get().file("dev/gaboron/spwmedia/protocol/BuildInfo.kt").asFile.apply {
                parentFile.mkdirs()
                writeText("package dev.gaboron.spwmedia.protocol\n/** Build version generated from gradle.properties. */\nobject BuildInfo { const val VERSION = \"${project.version}\" }\n")
            }
        }
    }
    extensions.configure<KotlinJvmProjectExtension> { sourceSets.named("main") { kotlin.srcDir(generated) } }
    tasks.named("compileKotlin") { dependsOn(generateVersion) }
    tasks.named("sourcesJar") { dependsOn(generateVersion) }
    tasks.matching { it.name.startsWith("dokka") }.configureEach { dependsOn(generateVersion) }
}
project(":provider-api") { dependencies { "api"(project(":protocol")) } }
project(":runtime-core") { dependencies { "api"(project(":provider-api")) } }
project(":runtime-core") {
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        doFirst { systemProperty("spw.test.classpath", project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath.asPath) }
    }
}
project(":native-worker") { dependencies { "implementation"(project(":protocol")); "implementation"("net.java.dev.jna:jna:5.18.1") } }
listOf("processing", "playback", "web", "metadata", "audio", "rendering").forEach { module ->
    project(":providers:$module") { dependencies { "implementation"(project(":provider-api")) } }
}
project(":providers:metadata") { dependencies { "implementation"("net.jthink:jaudiotagger:3.0.1") } }
project(":spw-adapter") {
    dependencies {
        "implementation"(project(":runtime-core"))
        "runtimeOnly"(project(":native-worker"))
        listOf("processing", "playback", "web", "metadata", "audio", "rendering").forEach { "implementation"(project(":providers:$it")) }
        "compileOnly"(hostApi) { isTransitive = false }
        "compileOnly"("org.pf4j:pf4j:3.12.0")
    }
    tasks.named<Jar>("jar") {
        manifest.attributes(mapOf("Plugin-Id" to "spw-media-runtime", "Plugin-Class" to "dev.gaboron.spwmedia.host.MediaRuntimePlugin", "Plugin-Version" to project.version, "Plugin-Provider" to "GaBoron", "Plugin-Name" to "SPW Media Runtime", "Plugin-Description" to "Shared media services for SPW plugins", "Plugin-Has-Config" to "false", "Plugin-Open-Source-Url" to "https://github.com/GaBoron/SPW-Media-Runtime"))
    }
}
project(":sdk-compose") {
    apply(plugin = "org.jetbrains.kotlin.plugin.compose")
    apply(plugin = "org.jetbrains.compose")
    dependencies {
        "api"(project(":sdk"))
        "compileOnly"("org.jetbrains.compose.desktop:desktop:1.12.1")
    }
}
project(":examples") {
    apply(plugin = "org.jetbrains.kotlin.plugin.compose")
    apply(plugin = "org.jetbrains.compose")
    dependencies {
        "implementation"(project(":sdk"))
        "implementation"(project(":sdk-compose"))
        "compileOnly"("org.jetbrains.compose.desktop:desktop:1.12.1")
        "compileOnly"(hostApi) { isTransitive = false }
        "compileOnly"("org.pf4j:pf4j:3.12.0")
    }
}

val runtimeClasspath = configurations.create("deliveryRuntime") {
    isCanBeConsumed = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
dependencies.add(runtimeClasspath.name, project(":spw-adapter"))
dependencies.add(runtimeClasspath.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
val exampleClasspath = configurations.create("exampleRuntime") {
    isCanBeConsumed = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
val maintenanceClasspath = configurations.create("maintenanceRuntime") {
    isCanBeConsumed = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
dependencies.add(maintenanceClasspath.name, project(":runtime-core"))
dependencies.add(maintenanceClasspath.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
dependencies.add(exampleClasspath.name, project(":sdk"))
dependencies.add(exampleClasspath.name, project(":sdk-compose"))
tasks.register<Exec>("prepareNative") {
    commandLine("pwsh", "-NoProfile", "-File", "native/prepare.ps1")
}
tasks.register<Zip>("plugin") {
    dependsOn("nativeManifest", ":spw-adapter:jar", ":native-worker:jar")
    archiveFileName.set("spw-media-runtime-${project.version}.spmod")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("classes") { from(project(":spw-adapter").tasks.named<Jar>("jar").map { zipTree(it.archiveFile) }) }
    into("lib") { from(runtimeClasspath); exclude("kotlin-stdlib-*.jar", "annotations-*.jar", "spw-media-spw-adapter-*.jar") }
    into("native") {
        from(layout.buildDirectory.dir("native"))
        include {
            val relative = it.relativePath.pathString
            !relative.startsWith("linux-x64/") || it.isDirectory || relative == "linux-x64/asset-manifest.properties" || file("build/native/linux-x64/packaged-files.txt").readLines().contains(relative.removePrefix("linux-x64/"))
        }
    }
    into("licenses") { from("LICENSE", "NOTICE", "third-party") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doFirst {
        listOf("windows-x64/ffmpeg.exe", "windows-x64/ffprobe.exe", "windows-x64/libmpv-2.dll", "windows-x64/spw-platform.exe", "windows-x64/spw-recycle.exe", "windows-x64/spw-jvm-worker.exe", "linux-x64/bin/ffmpeg", "linux-x64/bin/ffprobe", "linux-x64/lib/libmpv.so.2", "linux-x64/bin/spw-jvm-worker").forEach {
            check(layout.buildDirectory.file("native/$it").get().asFile.isFile) { "Missing native asset: $it" }
        }
    }
}
tasks.register<Exec>("nativeManifest") {
    dependsOn("prepareNative")
    commandLine("pwsh", "-NoProfile", "-File", "native/manifest.ps1")
}
tasks.register<JavaExec>("cacheCleanup") {
    dependsOn(":runtime-core:classes")
    classpath = maintenanceClasspath
    mainClass.set("dev.gaboron.spwmedia.core.CacheCleanupKt")
    systemProperty("spw.media.recycle.helper", file("build/native/windows-x64/spw-recycle.exe").absolutePath)
    javaLauncher.set(project(":runtime-core").extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(providers.gradleProperty("runtimeDataDir").getOrElse(""), providers.gradleProperty("cacheGeneration").getOrElse(""), providers.gradleProperty("applyCleanup").getOrElse("false"))
}
tasks.register<Zip>("sdkBundle") {
    dependsOn(subprojects.filter { it.name in setOf("protocol", "sdk", "sdk-compose", "provider-api") }.map { "${it.path}:publishAllPublicationsToDeliveryRepository" })
    archiveFileName.set("spw-media-sdk-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("maven"))
}
tasks.register("delivery") {
    dependsOn(subprojects.filter { it.path != ":providers" }.map { "${it.path}:check" })
    dependsOn("plugin", "sdkBundle", "apiBundle", "nativeSources", "examplePlugin", "parallelExamplePlugin", ":examples:classes")
}
tasks.register<Zip>("apiBundle") {
    listOf("sdk", "sdk-compose", "protocol", "provider-api").forEach { module ->
        dependsOn(":$module:dokkaGenerate")
        into(module) { from(project(":$module").layout.buildDirectory.dir("dokka/html")) }
    }
    archiveFileName.set("spw-media-api-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
tasks.register<Exec>("nativeSources") {
    dependsOn("prepareNative")
    commandLine("pwsh", "-NoProfile", "-File", "native/sources.ps1")
}
tasks.register<Copy>("stageJvm") {
    dependsOn(":spw-adapter:jar", ":native-worker:jar", ":sdk:jar")
    into(layout.buildDirectory.dir("jvm"))
    from(runtimeClasspath)
    from(project(":sdk").tasks.named("jar"))
    from(project(":spw-adapter").tasks.named("jar"))
    from(configurations.detachedConfiguration(dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")))
}
project(":examples").tasks.named<Jar>("jar") {
    manifest.attributes(mapOf("Plugin-Id" to "spw-media-example", "Plugin-Class" to "dev.gaboron.spwmedia.examples.MediaExamplePlugin", "Plugin-Version" to project.version, "Plugin-Provider" to "GaBoron", "Plugin-Name" to "SPW Media Example", "Plugin-Has-Config" to "true"))
}
tasks.register<Zip>("examplePlugin") {
    dependsOn(":examples:jar", ":sdk:jar", ":protocol:jar")
    archiveFileName.set("spw-media-example-${project.version}.spmod")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("classes") { from(project(":examples").tasks.named<Jar>("jar").map { zipTree(it.archiveFile) }) }
    into("lib") {
        from(project(":sdk").tasks.named("jar")); from(project(":protocol").tasks.named("jar"))
        from(exampleClasspath)
        exclude("kotlin-stdlib-*.jar", "annotations-*.jar")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.register<GradleBuild>("parallelExamplePlugin") {
    dependsOn("sdkBundle")
    dir = file("examples/parallel-consumer")
    tasks = listOf("plugin")
    startParameter.projectProperties = mapOf("sdkRepository" to file("build/maven").toURI().toString(), "sdkVersion" to project.version.toString(), "outputDir" to file("build/distributions").absolutePath)
    startParameter.systemPropertiesArgs = gradle.startParameter.systemPropertiesArgs
    startParameter.setOffline(gradle.startParameter.isOffline)
}

apply(from = "gradle/package-checks.gradle.kts")
apply(from = "gradle/consumer-smoke.gradle.kts")

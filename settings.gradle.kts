pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        maven("https://jitpack.io")
        google()
    }
}
rootProject.name = "spw-media-runtime"
include("protocol", "sdk", "sdk-compose", "provider-api", "runtime-core", "native-worker", "spw-adapter", "verification")
listOf("processing", "playback", "web", "metadata", "audio", "rendering").forEach { include("providers:$it") }

// A release consumes this explicit list, never every file left in build/distributions.
tasks.register("releaseAssetManifest") {
    dependsOn("plugin", "sdkBundle", "apiBundle", "nativeSources", "checkPackages", "checkSdkArtifacts")
    doLast {
        val directory = layout.buildDirectory.dir("distributions").get().asFile
        val version = project.version.toString()
        val assets = listOf("spw-media-runtime-$version.spmod", "spw-media-sdk-$version.zip", "spw-media-api-$version.zip", "spw-media-native-sources-$version.zip")
        assets.forEach { check(directory.resolve(it).isFile) { "Missing release asset: $it" } }
        // Reuse is valid only for the exact v0.1.0 upstream snapshot and build inputs.
        listOf("native/prepare.ps1", "native/linux/build.py").forEach {
            check(file(it).readText().contains("autobuild-2026-10-20-13-08/ffmpeg-n8.1.3-18-g46d899d7a0-")) { "FFmpeg origin changed: provide matching dependency sources and update the release references" }
        }
        check(file("native/source_bundle.py").readText().contains("artifacts/12263308818/zip")) { "FFmpeg source-cache origin changed; update the release references" }
        val references = (1..3).map { "https://github.com/GaBoron/SPW-Media-Runtime/releases/download/v0.1.0/spw-media-ffmpeg-dependency-sources-0.1.0.zip.00$it" }
        directory.resolve("release-assets-$version.json").writeText(
            "{\n  \"version\": \"$version\",\n  \"assets\": [\n" + assets.joinToString(",\n") { "    \"$it\"" } +
                "\n  ],\n  \"ffmpegDependencySources\": [\n" + references.joinToString(",\n") { "    \"$it\"" } + "\n  ]\n}\n"
        )
        logger.lifecycle("Release asset list verified; examples excluded, unchanged FFmpeg dependency sources reference v0.1.0")
    }
}
tasks.named("delivery") { dependsOn("releaseAssetManifest") }

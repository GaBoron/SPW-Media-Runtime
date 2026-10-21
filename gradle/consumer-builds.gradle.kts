// Independent builds resolve public Maven coordinates rather than project dependencies.
fun consumerBuild(name: String, directory: String, requestedTasks: List<String>) = tasks.register<GradleBuild>(name) {
    dependsOn("sdkBundle")
    dir = file(directory)
    tasks = requestedTasks
    startParameter.projectProperties = mapOf(
        "sdkRepository" to rootProject.file("build/maven").toURI().toString(),
        "sdkVersion" to project.version.toString(), "sdkGroup" to project.group.toString()
    )
    startParameter.systemPropertiesArgs = gradle.startParameter.systemPropertiesArgs
    startParameter.setOffline(gradle.startParameter.isOffline)
}
consumerBuild("checkExample", "examples", listOf("classes"))
consumerBuild("examplePlugin", "examples", listOf("plugin"))
consumerBuild("consumerFixture", "verification/consumer-fixture", listOf("fixture", "checkPublicModules"))

import java.nio.file.Files

project(":verification") {
    tasks.register<JavaExec>("consumerSmoke") {
        dependsOn(":checkPackages", ":consumerFixture", "classes")
        classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
        mainClass.set("dev.gaboron.spwmedia.verification.ConsumerSmoke")
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
        args(rootProject.layout.buildDirectory.dir("distributions").get().asFile.absolutePath, rootProject.version.toString(), rootProject.file("verification/consumer-fixture/build/fixture/consumer-fixture-${rootProject.version}.zip").absolutePath)
        systemProperty("spw.media.recycle.helper", rootProject.file("build/native/windows-x64/spw-recycle.exe").absolutePath)
        providers.gradleProperty("smokeJavaHome").orNull?.let { systemProperty("spw.smoke.javaHome", it) }
        doFirst {
            systemProperty("java.io.tmpdir", Files.createTempDirectory("consumer-smoke-").toAbsolutePath().toString())
        }
    }
}
tasks.register("consumerSmoke") { dependsOn(":verification:consumerSmoke") }
tasks.named("delivery") { dependsOn(":verification:consumerSmoke") }

plugins {
    application
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation(project(":hybrid-common"))
    testImplementation(project(":hybrid-fabric"))
    testImplementation(project(":hybrid-neoforge"))
}

application {
    mainClass.set("cloud.victus.hybrid.launcher.VictusHybridLauncher")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

val launcherSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs dependency-free hybrid launcher tests."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.launcher.HybridLauncherSelfTest")
}

val launcherIsolationSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Proves disabled mode has no adapter or loader classes on its production classpath."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.main.get().runtimeClasspath + sourceSets.test.get().output
    mainClass.set("cloud.victus.hybrid.launcher.LauncherIsolationSelfTest")
}

tasks.named("test") {
    dependsOn(launcherSelfTest, launcherIsolationSelfTest)
}

val boundedIntegrationTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs bounded profile preflight/integration assertions without starting Minecraft."
    dependsOn(tasks.named("testClasses"), ":hybrid-fixtures:fixtureArtifacts")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.launcher.BoundedHybridIntegrationTest")
    args(rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("victus.hybrid.integration.timeoutSeconds", "20")
}

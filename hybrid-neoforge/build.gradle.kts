plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    api(project(":hybrid-common"))
    // FML stays outside the launcher classpath until the NeoForge profile is selected.
}

val python = providers.environmentVariable("PYTHON").orElse("python")
val pipeline = rootProject.layout.projectDirectory.file("scripts/hybrid/neoforge_pipeline.py")
val neoBuild = layout.buildDirectory.dir("neoforge")

fun registerPipelineTask(name: String, descriptionText: String, vararg command: String) =
    tasks.register<Exec>(name) {
        group = if (name.contains("Validate") || name.contains("Check") || name.contains("Integration")) "verification" else "hybrid"
        description = descriptionText
        commandLine(python.get(), pipeline.asFile.absolutePath, *command)
    }

val neoforgeValidateLock = registerPipelineTask(
    "neoforgeValidateLock",
    "Validates the installer-derived NeoForge 26.2.0.57 lock without downloading artifacts.",
    "validate-lock"
)

val neoforgeResolveLockedRuntime = registerPipelineTask(
    "neoforgeResolveLockedRuntime",
    "Downloads and verifies every locked NeoForge installer/runtime/source-machine input under build/.",
    "validate-lock", "--resolve"
)

val neoforgeReconstructPatchedGame = registerPipelineTask(
    "neoforgeReconstructPatchedGame",
    "Runs the locked installer processors and verifies the reconstructed patched server hash.",
    "reconstruct-binary"
)

val neoforgeReconstructSources = registerPipelineTask(
    "neoforgeReconstructSources",
    "Reconstructs NeoForge-patched named Minecraft sources with the locked NeoForm source machine.",
    "reconstruct-sources"
)

val neoforgeMergeSources by tasks.registering(Exec::class) {
    group = "hybrid"
    description = "Three-way compares NeoForge and Paper/Victus named Minecraft sources and writes conflicts.json."
    dependsOn(neoforgeReconstructSources)
    val vanilla = providers.gradleProperty("neoforgeVanillaSources")
        .orElse(neoBuild.map { it.file("sources/vanilla-sources.jar").asFile.absolutePath })
    val neoforge = providers.gradleProperty("neoforgePatchedSources")
        .orElse(neoBuild.map { it.file("sources/neoforge-game-sources.jar").asFile.absolutePath })
    val paper = providers.gradleProperty("victusPatchedSources")
        .orElse(rootProject.layout.projectDirectory.dir("victus-server/src/minecraft/java").asFile.absolutePath)
    commandLine(
        python.get(), pipeline.asFile.absolutePath, "merge",
        "--vanilla", vanilla.get(), "--neoforge", neoforge.get(), "--paper", paper.get(),
        "--allow-conflicts"
    )
}

val neoforgeCheckMerge = registerPipelineTask(
    "neoforgeCheckMerge",
    "Fails closed unless the machine-readable NeoForge/Paper merge report has no unresolved conflicts.",
    "check-merge"
)

val neoForgeAdapterSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.neoforge.NeoForgeAdapterSelfTest")
}

val neoforgeExpectedFailIntegration by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the bounded NeoForge lifecycle/transformation fixture proof; records expected merge blocker."
    dependsOn(":hybrid-fixtures:neoForgeFixtureJar", neoforgeValidateLock)
    val fixture = project(":hybrid-fixtures").layout.buildDirectory.file("libs/victus-fixture-neoforge-${project.version}.jar")
    commandLine(
        python.get(), rootProject.layout.projectDirectory.file("scripts/hybrid/run-neoforge-integration.py").asFile.absolutePath,
        "--fixture", fixture.get().asFile.absolutePath,
        "--report", neoBuild.get().file("integration/result.json").asFile.absolutePath
    )
}

val neoforgeBoundedIntegration by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the real bounded fixture gate against -PneoforgeMergedServer."
    dependsOn(":hybrid-fixtures:neoForgeFixtureJar", neoforgeValidateLock)
    val fixture = project(":hybrid-fixtures").layout.buildDirectory.file("libs/victus-fixture-neoforge-${project.version}.jar")
    val mergedServer = providers.gradleProperty("neoforgeMergedServer").orElse(
        neoBuild.map { it.file("integration/merged-server-missing.jar").asFile.absolutePath }
    )
    commandLine(
        python.get(), rootProject.layout.projectDirectory.file("scripts/hybrid/run-neoforge-integration.py").asFile.absolutePath,
        "--fixture", fixture.get().asFile.absolutePath,
        "--report", neoBuild.get().file("integration/bounded-result.json").asFile.absolutePath,
        "--timeout", providers.gradleProperty("neoforgeIntegrationTimeout").orElse("90").get(),
        "--server", mergedServer.get()
    )
}

val neoforgeBindMergedServer by tasks.registering(Exec::class) {
    group = "hybrid"
    description = "Binds a conflict-clean merge report to -PneoforgeMergedServer by SHA-256."
    val server = providers.gradleProperty("neoforgeMergedServer").orElse(
        neoBuild.map { it.file("integration/merged-server-missing.jar").asFile.absolutePath }
    )
    commandLine(python.get(), pipeline.asFile.absolutePath, "bind-server", "--server", server.get())
}

val neoforgeHybridDistribution by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Assembles a fail-closed NeoForge hybrid distribution only after the source merge is clean."
    dependsOn(neoforgeCheckMerge, neoforgeReconstructPatchedGame, ":hybrid-fixtures:neoForgeFixtureJar")
    val server = providers.gradleProperty("victusServerJar")
        .orElse(rootProject.layout.projectDirectory.file("victus-server/build/libs/victus-server.jar").asFile.absolutePath)
    val fixture = project(":hybrid-fixtures").layout.buildDirectory.file("libs/victus-fixture-neoforge-${project.version}.jar")
    commandLine(
        python.get(), pipeline.asFile.absolutePath, "assemble",
        "--server", server.get(), "--fixture", fixture.get().asFile.absolutePath
    )
}

tasks.named("test") {
    dependsOn(neoForgeAdapterSelfTest, neoforgeValidateLock, neoforgeExpectedFailIntegration)
}

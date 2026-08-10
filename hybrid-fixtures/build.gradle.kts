plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val fabric by sourceSets.creating
val neoforge by sourceSets.creating
val bukkit by sourceSets.creating

for (sourceSet in listOf(neoforge, bukkit)) {
    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        options.release.set(25)
    }
}

dependencies {
    "fabricCompileOnly"("net.fabricmc:fabric-loader:0.19.3")
    "neoforgeCompileOnly"("net.neoforged.fancymodloader:loader:11.0.17")
    // Compile the fixture against Paper's published API instead of the generated :victus-api
    // project output. A fresh clone has no hydrated paper-api sources until patch application,
    // while the fixture only needs the stable JavaPlugin contract.
    "bukkitCompileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// Fixtures compile against the real loader/plugin entrypoint APIs but contain only owned source and metadata.

val fabricFixtureJar by tasks.registering(Jar::class) {
    group = "build"
    archiveBaseName.set("victus-fixture-fabric")
    from(fabric.output)
}

val neoForgeFixtureJar by tasks.registering(Jar::class) {
    group = "build"
    archiveBaseName.set("victus-fixture-neoforge")
    from(neoforge.output)
}

val bukkitFixtureJar by tasks.registering(Jar::class) {
    group = "build"
    archiveBaseName.set("victus-fixture-bukkit")
    from(bukkit.output)
}

val fixtureArtifacts by tasks.registering {
    group = "build"
    description = "Builds owned Fabric, NeoForge, and Bukkit marker fixtures."
    dependsOn(fabricFixtureJar, neoForgeFixtureJar, bukkitFixtureJar)
}

val fixtureSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.named("testClasses"), fixtureArtifacts)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.fixtures.HybridFixturesSelfTest")
    args(layout.buildDirectory.dir("libs").get().asFile.absolutePath)
}

tasks.named("test") {
    dependsOn(fixtureSelfTest)
}

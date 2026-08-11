plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    api(project(":hybrid-common"))
    // Compile against Loader's maintained GameProvider SPI. It remains compileOnly so disabled mode and
    // the launcher production classpath cannot acquire Fabric transitively; the locked runtime supplies it.
    compileOnly("net.fabricmc:fabric-loader:0.19.3")
    compileOnly("org.ow2.asm:asm:9.10.1")
    compileOnly("org.ow2.asm:asm-tree:9.10.1")
    testImplementation("net.fabricmc:fabric-loader:0.19.3")
    testImplementation("org.ow2.asm:asm:9.10.1")
    testImplementation("org.ow2.asm:asm-tree:9.10.1")
}

val fabricAdapterSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.fabric.FabricAdapterSelfTest")
}

val fabricCompatibilityValidatorSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.fabric.FabricApiCompatibilityValidatorSelfTest")
}

val fabricAggregateFilterSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    commandLine(providers.environmentVariable("PYTHON").orElse("python").get(),
        rootProject.file("scripts/hybrid/test-prepare-fabric-runtime.py"))
}

val fabricLoaderVersion = "0.19.3"
val fabricMixinVersion = "0.17.3+mixin.0.8.7"
val fabricRuntime by configurations.creating

dependencies {
    fabricRuntime("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    fabricRuntime("org.ow2.asm:asm:9.10.1")
    fabricRuntime("org.ow2.asm:asm-analysis:9.10.1")
    fabricRuntime("org.ow2.asm:asm-commons:9.10.1")
    fabricRuntime("org.ow2.asm:asm-tree:9.10.1")
    fabricRuntime("org.ow2.asm:asm-util:9.10.1")
    fabricRuntime("net.fabricmc:sponge-mixin:$fabricMixinVersion") { isTransitive = false }
    fabricRuntime("net.fabricmc.fabric-api:fabric-api-base:2.0.4+ece063239e") { isTransitive = false }
    fabricRuntime("net.fabricmc.fabric-api:fabric-lifecycle-events-v1:4.1.3+4575b05f9e") { isTransitive = false }
}

val fabricFixturePath = rootProject.layout.projectDirectory.file(
    "hybrid-fixtures/build/libs/victus-fixture-fabric-${project.version}.jar"
)
val fullFabricApi = configurations.detachedConfiguration(
    dependencies.create("net.fabricmc.fabric-api:fabric-api:0.156.0+26.2")
).apply { isTransitive = false }

val fabricProviderIntegrationTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs real Fabric Loader/Knot lifecycle and Mixin against an owned class-bearing target."
    dependsOn(tasks.named("testClasses"), tasks.named("jar"), ":hybrid-fixtures:fabricFixtureJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.fabric.VictusFabricProviderIntegrationTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(
        layout.buildDirectory.file("libs/hybrid-fabric-${project.version}.jar").get().asFile.absolutePath,
        fabricFixturePath.asFile.absolutePath,
        fabricRuntime.asPath
    )
}

val fabricApiCompatibilityIntegrationTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Audits the full Fabric API aggregate and refuses absent Minecraft/Paper mixin targets."
    dependsOn(tasks.named("testClasses"), tasks.named("jar"), ":hybrid-fixtures:fabricFixtureJar")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.fabric.VictusFabricProviderIntegrationTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(
        layout.buildDirectory.file("libs/hybrid-fabric-${project.version}.jar").get().asFile.absolutePath,
        fabricFixturePath.asFile.absolutePath,
        fabricRuntime.asPath,
        fullFabricApi.singleFile.absolutePath
    )
}

val fabricDistribution by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Creates a verified Fabric hybrid distribution for -PfabricTarget=<class-bearing victus-server jar>."
    dependsOn(
        rootProject.tasks.named("resolveFabricRuntime"), tasks.named("jar"),
        ":hybrid-common:jar", ":hybrid-launcher:jar", ":hybrid-fixtures:fabricFixtureJar"
    )
    val target = providers.gradleProperty("fabricTarget").orElse("")
    val targetClasspathFile = providers.gradleProperty("fabricTargetClasspathFile").orElse("")
    commandLine(
        providers.environmentVariable("PYTHON").orElse("python").get(), rootProject.file("scripts/hybrid/prepare-fabric-runtime.py"),
        "--target", target.get(),
        "--resolved", rootProject.layout.buildDirectory.dir("hybrid-resolved").get().asFile.absolutePath,
        "--output", rootProject.layout.buildDirectory.dir("hybrid-fabric-dist").get().asFile.absolutePath
    )
    if (targetClasspathFile.get().isNotBlank()) {
        args("--target-classpath", targetClasspathFile.get())
    }
}

tasks.named("test") {
    dependsOn(fabricAdapterSelfTest, fabricCompatibilityValidatorSelfTest, fabricAggregateFilterSelfTest,
        fabricProviderIntegrationTest, fabricApiCompatibilityIntegrationTest)
}

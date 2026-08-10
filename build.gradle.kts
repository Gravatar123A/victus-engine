// === Victus Engine — root build (paperweight-patcher 2.0) ===
// Mirrors the current Paper hard-fork fork convention. paperweight fetches Paper at `paperCommit`,
// decompiles it, applies our patches, and builds the server jar. Paper source is never vendored.

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.21"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    upstreams.paper {
        ref = providers.gradleProperty("paperCommit")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("victus-server/build.gradle.kts")
            patchFile = file("victus-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("victus-api/build.gradle.kts")
            patchFile = file("victus-api/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("victus-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

subprojects {
    apply(plugin = "java-library")

    val java21Foundation = name.startsWith("hybrid-") || name == "victus-core"
    extensions.configure<JavaPluginExtension> {
        toolchain {
            // Paper 26.2 requires JDK 25; dependency-free core/pre-main modules stay Java 21-compatible.
            languageVersion = JavaLanguageVersion.of(if (java21Foundation) 21 else 25)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = if (java21Foundation) 21 else 25
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
    if (name.startsWith("hybrid-")) {
        tasks.withType<Test>().configureEach {
            // Hybrid modules use dependency-free executable self-tests to keep pre-main code lean.
            failOnNoDiscoveredTests = false
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
    }
}

// The hybrid launcher/modules are intentionally independent from Paper's patched source graph. This
// aggregate is the bounded foundation gate used by CI and local development for task #19.
tasks.register("hybridCheck") {
    group = "verification"
    description = "Builds and tests the isolated hybrid runtime foundation and owned fixtures."
    dependsOn(
        ":hybrid-common:test",
        ":hybrid-launcher:test",
        ":hybrid-fabric:test",
        ":hybrid-neoforge:test",
        ":hybrid-fixtures:fixtureArtifacts",
        ":hybrid-launcher:boundedIntegrationTest"
    )
}

tasks.register("neoforgeHybridCheck") {
    group = "verification"
    description = "Validates locked NeoForge merge machinery and the bounded expected-fail fixture runner."
    dependsOn(
        ":hybrid-neoforge:test",
        ":hybrid-neoforge:neoforgeValidateLock",
        ":hybrid-neoforge:neoforgeExpectedFailIntegration"
    )
}

tasks.register<Exec>("resolveFabricRuntime") {
    group = "distribution"
    description = "Downloads the locked Fabric runtime into build/hybrid-resolved with size/SHA-256 verification."
    commandLine(
        "python", file("scripts/hybrid/validate-runtime-lock.py"), "--resolve", "--profile", "fabric",
        "--output", layout.buildDirectory.dir("hybrid-resolved").get().asFile.absolutePath
    )
}

tasks.register("printVictusVersion") {
    doLast { println(project.version) }
}

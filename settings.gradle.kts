// === Victus Engine — settings ===
// Fork of PaperMC (Paper 26.2 base) built with paperweight-patcher 2.0.
// Structure mirrors the current Paper hard-fork convention (see PurpurMC/Purpur ver/26.2).

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    // Auto-provisions the JDK 25 toolchain Paper 26.2 requires (needs internet on first build).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    error(
        "\n=====================[ ERROR ]=====================\n" +
        " Victus Engine must be built from a Git CLONE, not a zip download —\n" +
        " paperweight needs git to fetch and patch Paper source.\n" +
        "===================================================\n"
    )
}

rootProject.name = "victus-engine"
for (name in listOf(
    "victus-api",
    "victus-server",
    "victus-core",
    "hybrid-common",
    "hybrid-relocator",
    "hybrid-bukkit",
    "hybrid-launcher",
    "hybrid-fabric",
    "hybrid-neoforge",
    "hybrid-fixtures"
)) {
    include(name)
    findProject(":$name")!!.projectDir = file(name)
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val build = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toIntOrNull()
    version = if (build == null) "$mcVersion.local-SNAPSHOT" else "$mcVersion.build.$build"
}

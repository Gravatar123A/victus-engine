// === Victus Engine — settings ===
// Fork of PaperMC built with the paperweight-patcher toolchain.
//
// IMPORTANT (read docs/ARCHITECTURE.md "Build system" section):
// The paperweight-patcher DSL changed across Paper's Dec-2024 hard fork. This file applies the
// plugin (confident) but leaves the upstream/patch configuration as a TODO block you must fill
// from the CURRENT docs for your Paper base:
//   https://docs.papermc.io/paper/dev/getting-started/paperweight-patcher
// This scaffold was authored without network access, so the exact `paperweight { ... }` shape
// is intentionally not guessed here — a wrong guess would fail to compile.

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    // TODO(verify): pin to the paperweight-patcher version matching your Paper base.
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.18"
}

rootProject.name = "victus-engine"

// ---------------------------------------------------------------------------------------------
// TODO(verify): configure the Paper upstream + patch sets here, per the paperweight-patcher docs.
// Sketch of the intended shape (confirm exact API before uncommenting):
//
// paperweight {
//     upstreams.register("paper") {
//         ref = providers.gradleProperty("paperRef")            // ver/26.1
//         patchFile {
//             path = "paper-server/build.gradle.kts.patch"
//             outputFile = file("paper-server/build.gradle.kts")
//         }
//         // ...api + server patch directories: patches/paper-api, patches/paper-server
//     }
// }
// ---------------------------------------------------------------------------------------------

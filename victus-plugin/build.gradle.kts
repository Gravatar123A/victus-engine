// Victus Engine plugin — standalone Gradle build (independent of the fork build so it can't disturb
// paperweight). Compiles against the Paper API and bundles victus-core's source directly (no shadow
// plugin needed), producing a single drop-in plugin jar.

plugins {
    java
}

group = "cloud.victus"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25)) // Paper 26.2 API targets JDK 25
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Provided by the server at runtime. Version tracks our Paper 26.2 base.
    compileOnly("io.papermc.paper:paper-api:26.2.build.62-beta")
}

sourceSets["main"].java {
    // bundle victus-core (the Paper-independent core logic) straight into the plugin jar,
    // minus its offline self-tests
    srcDir("../victus-core/src/main/java")
    exclude("**/*SelfTest.java")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("VictusEngine")
}

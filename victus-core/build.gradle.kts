// victus-core — Paper-independent, dependency-free engine logic.
//
// Everything here (config resolution, metric formatting, remediation rules, throttle math) is pure
// JDK code with NO dependency on Paper/Bukkit/Minecraft, so it can be developed and unit-tested
// OFFLINE with just `javac`/`java` (see README.md). The paperweight patches wire this library into
// the server; keeping logic here maximizes the buildable/testable-without-internet surface.

plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Zero runtime dependencies by design.
    // Once building online, replace ConfigSelfTest's main() with JUnit:
    // testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

val fabricLifecycleBridgeSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cloud.victus.core.runtime.FabricLifecycleBridgeSelfTest")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(fabricLifecycleBridgeSelfTest)
}

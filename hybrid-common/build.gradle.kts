plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.test {
    // Tests are deterministic, dependency-free executable contracts.
}

val commonSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs dependency-free hybrid common contract tests."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.common.HybridCommonSelfTest")
}

tasks.named("test") {
    dependsOn(commonSelfTest)
}

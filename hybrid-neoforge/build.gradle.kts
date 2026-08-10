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

val neoForgeAdapterSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.neoforge.NeoForgeAdapterSelfTest")
}

tasks.named("test") {
    dependsOn(neoForgeAdapterSelfTest)
}

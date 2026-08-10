plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    api(project(":hybrid-common"))
    // Intentionally not a compile/runtime dependency. The adapter touches Knot only after isolated
    // profile selection and verifies the operator-supplied runtime against hybrid/locks/runtime-lock.json.
}

val fabricAdapterSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.fabric.FabricAdapterSelfTest")
}

tasks.named("test") {
    dependsOn(fabricAdapterSelfTest)
}

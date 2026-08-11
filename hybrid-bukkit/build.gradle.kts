plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    api(project(":hybrid-common"))
}

val bridgeSelfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs loader-neutral Bukkit bridge contract and fixture tests."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cloud.victus.hybrid.bukkit.HybridBukkitSelfTest")
}

tasks.named("test") {
    dependsOn(bridgeSelfTest)
}

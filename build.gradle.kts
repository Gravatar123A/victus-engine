// === Victus Engine — root build ===
// In a paperweight-patcher fork most subproject wiring is provided by the plugin. This root
// build only sets shared coordinates and the Java toolchain. Server/api specifics live in the
// patched Paper subprojects and in patches/ .

plugins {
    java
}

allprojects {
    group = property("group") as String
    version = property("version") as String
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21) // TODO(verify): match the JDK Paper 26.1 requires (>=21).
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
}

// Victus Engine target JDK. Java 21 is present locally; Paper 26.x may require a newer JDK —
// bump the toolchain if applyPatches complains.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

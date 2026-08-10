// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

public final class HybridFixturesSelfTest {
    public static void main(String[] args) throws Exception {
        Path libs = Path.of(args[0]);
        Path fabric = one(libs, "victus-fixture-fabric");
        Path neo = one(libs, "victus-fixture-neoforge");
        Path bukkit = one(libs, "victus-fixture-bukkit");
        try (JarFile jar = new JarFile(fabric.toFile())) {
            check(jar.getEntry("fabric.mod.json") != null, "Fabric metadata");
            check(jar.getEntry("victus-fixture.mixins.json") != null, "Fabric mixin marker config");
            check(jar.getEntry("cloud/victus/hybrid/fixtures/fabric/FixtureFabricMod.class") != null, "Fabric entrypoint class");
            check(jar.getEntry("cloud/victus/hybrid/fixtures/fabric/mixin/FixtureMarkerMixin.class") != null, "real Fabric Mixin class");
        }
        try (JarFile jar = new JarFile(neo.toFile())) {
            check(jar.getEntry("META-INF/neoforge.mods.toml") != null, "NeoForge metadata");
            check(jar.getEntry("cloud/victus/hybrid/fixtures/neoforge/FixtureClassProcessorMarker.class") != null,
                    "NeoForge class processor marker");
            check(jar.getEntry("cloud/victus/hybrid/fixtures/neoforge/FixtureMixinMarker.class") != null,
                    "NeoForge Mixin marker");
            check(jar.getEntry("META-INF/services/net.neoforged.neoforgespi.transformation.ClassProcessorProvider") != null,
                    "NeoForge class processor service");
            check(jar.getEntry("victus-fixture-neoforge.mixins.json") != null, "NeoForge Mixin config");
        }
        try (JarFile jar = new JarFile(bukkit.toFile())) {
            check(jar.getEntry("plugin.yml") != null, "Bukkit metadata");
            check(jar.getEntry("cloud/victus/hybrid/fixtures/bukkit/FixtureBukkitPlugin.class") != null, "Bukkit lifecycle class");
        }
        System.out.println("HybridFixturesSelfTest: 11 checks passed");
    }

    private static Path one(Path directory, String prefix) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)
                            && path.getFileName().toString().endsWith(".jar"))
                    .findFirst().orElseThrow(() -> new AssertionError("missing " + prefix));
        }
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}

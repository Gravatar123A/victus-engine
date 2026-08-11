/*
 * Copyright 2016 FabricMC
 * Copyright 2026 Victus Engine contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Source-compatible GameProvider implementation for Fabric Loader 0.19.3. The
 * interface and lifecycle ordering are derived from Fabric Loader's
 * MinecraftGameProvider; no Fabric source or binary is vendored here.
 */
package cloud.victus.hybrid.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.util.Arguments;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Fabric game provider for the already patched, Mojang-mapped Victus server jar.
 *
 * <p>The stock provider classifies vanilla/bundler shapes and patches a vanilla
 * main entrypoint. Victus instead supplies an explicit class-bearing server jar,
 * owns the lifecycle call before delegating to Paper, and never runs Paperclip.
 */
public final class VictusFabricGameProvider implements GameProvider {
    public static final String ENABLE_PROPERTY = "victus.fabric.provider";
    public static final String TARGET_JAR_PROPERTY = "victus.fabric.targetJar";
    public static final String TARGET_MAIN_PROPERTY = "victus.fabric.targetMain";
    public static final String TARGET_LIBRARIES_PROPERTY = "victus.fabric.targetLibraries";
    public static final String EXPECTED_TARGET_MAIN = "org.bukkit.craftbukkit.Main";
    public static final String OWNED_MARKER_CLASS = "cloud.victus.hybrid.fixtures.fabric.FixtureMixinMarker";
    public static final String EXTERNAL_LIBRARY_PROBE_PROPERTY = "victus.fabric.externalLibraryProbe";
    public static final String EXTERNAL_LIBRARY_MARKER = "VICTUS_EXTERNAL_LIBRARY_VISIBLE";

    private static final Set<String> SENSITIVE_ARGS = new HashSet<>(Arrays.asList(
            "accesstoken", "clientid", "profileproperties", "proxypass", "proxyuser",
            "username", "userproperties", "uuid", "xuid"));
    private static final Set<BuiltinTransform> GAME_TRANSFORMS = Collections.unmodifiableSet(
            EnumSet.of(BuiltinTransform.WIDEN_ALL_PACKAGE_ACCESS, BuiltinTransform.CLASS_TWEAKS));
    private static final Set<BuiltinTransform> MOD_TRANSFORMS = Collections.unmodifiableSet(
            EnumSet.of(BuiltinTransform.STRIP_ENVIRONMENT));

    private final GameTransformer transformer = new GameTransformer();
    private Arguments arguments;
    private Path targetJar;
    private List<Path> targetLibraries = List.of();
    private String targetMain;
    private Path launchDirectory;
    private final Set<Path> unlockedTargetClassPath = new LinkedHashSet<>();

    @Override
    public String getGameId() {
        return "minecraft";
    }

    @Override
    public String getGameName() {
        return "Victus";
    }

    @Override
    public String getRawGameVersion() {
        return FabricLoaderAdapter.MINECRAFT_VERSION;
    }

    @Override
    public String getNormalizedGameVersion() {
        return FabricLoaderAdapter.MINECRAFT_VERSION;
    }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
                .setName(getGameName());
        try {
            metadata.addDependency(new ModDependencyImpl(ModDependency.Kind.DEPENDS, "java", List.of(">=25")));
        } catch (VersionParsingException failure) {
            throw new IllegalStateException("invalid Victus Java dependency", failure);
        }
        return List.of(new BuiltinMod(List.of(targetJar), metadata.build()));
    }

    @Override
    public String getEntrypoint() {
        return targetMain;
    }

    @Override
    public Path getLaunchDirectory() {
        return launchDirectory == null ? Path.of(".").toAbsolutePath().normalize() : launchDirectory;
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        return className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")
                ? GAME_TRANSFORMS : MOD_TRANSFORMS;
    }

    @Override
    public boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        if (!isEnabled() || launcher.getEnvironmentType() != EnvType.SERVER) {
            return false;
        }
        arguments = new Arguments();
        arguments.parse(args);
        targetMain = System.getProperty(TARGET_MAIN_PROPERTY, EXPECTED_TARGET_MAIN);
        targetJar = requiredRegularFile(TARGET_JAR_PROPERTY);
        targetLibraries = parseLibraries(System.getProperty(TARGET_LIBRARIES_PROPERTY, ""));
        launchDirectory = Path.of(System.getProperty("victus.fabric.gameDir", "."))
                .toAbsolutePath().normalize();
        unlockedTargetClassPath.clear();

        if (launcher.getClassPath().stream().anyMatch(path -> path.equals(targetJar))) {
            throw new IllegalStateException("Victus target jar was exposed to the platform classpath before Knot ownership: " + targetJar);
        }
        if (!EXPECTED_TARGET_MAIN.equals(targetMain)) {
            throw new IllegalStateException("Victus Fabric provider requires target main "
                    + EXPECTED_TARGET_MAIN + ", got " + targetMain);
        }
        if (!containsClass(targetJar, targetMain)) {
            throw new IllegalStateException("Victus target is not a class-bearing patched server jar: "
                    + targetJar + " lacks " + targetMain + ". Paperclip/bundler launch artifacts are not supported.");
        }
        return true;
    }

    @Override
    public void initialize(FabricLauncher launcher) {
        // Empty transformer intentionally avoids vanilla EntrypointPatch assumptions. Mods and target
        // classes still pass through FabricTransformer and Mixin in KnotClassDelegate.
        transformer.locateEntrypoints(launcher, List.of(targetJar));
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        return transformer;
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
        // These are added only after Fabric discovery, resolution, Mixin bootstrap and transformer init.
        // Consequently Knot owns target class definitions and transformations before Paper/Minecraft loads.
        addTargetPath(launcher, targetJar);
        for (Path library : targetLibraries) {
            addTargetPath(launcher, library);
        }
        if (unlockedTargetClassPath.size() != targetLibraries.size() + 1
                || !unlockedTargetClassPath.contains(targetJar)
                || !unlockedTargetClassPath.containsAll(targetLibraries)) {
            throw new IllegalStateException("FABRIC_TARGET_CLASSPATH_UNLOCK_FAILED: expected target plus "
                    + targetLibraries.size() + " libraries, added " + unlockedTargetClassPath);
        }
        System.out.println("VICTUS_FABRIC_TARGET_CLASSPATH target=" + targetJar
                + " libraries=" + targetLibraries.size() + " knotEntries=" + unlockedTargetClassPath.size());
    }

    private void addTargetPath(FabricLauncher launcher, Path path) {
        if (!unlockedTargetClassPath.add(path)) {
            throw new IllegalStateException("FABRIC_TARGET_CLASSPATH_DUPLICATE: " + path);
        }
        launcher.addToClassPath(path);
    }

    @Override
    public void launch(ClassLoader loader) {
        try {
            // The stock vanilla provider injects Hooks.startServer into vanilla bytecode. Paper has a
            // different startup shape, so this provider owns the equivalent loader lifecycle call.
            FabricLoaderImpl fabric = FabricLoaderImpl.INSTANCE;
            fabric.prepareModInit(getLaunchDirectory(), null);
            validateTargetLibraries(loader);
            FabricApiCompatibilityValidator.validate(fabric, targetJar, targetLibraries,
                    getLaunchDirectory().resolve("fabric-api-compatibility.json"));
            fabric.invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);

            verifyFixtureProof(loader, fabric);

            Class<?> target = loader.loadClass(targetMain);
            if (target.getClassLoader() != loader) {
                throw new IllegalStateException("Victus target escaped Knot ownership: "
                        + target.getClassLoader() + " != " + loader);
            }
            Method main = target.getMethod("main", String[].class);
            main.invoke(null, (Object) arguments.toArray());
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Victus target main failed", cause);
        } catch (Throwable failure) {
            // Preserve the exact provider blocker for VictusHybridLauncher instead of allowing Knot's
            // uncaught-exception handler to terminate the process before its startup report is written.
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Victus target launch failed", failure);
        }
    }

    private void validateTargetLibraries(ClassLoader loader) throws Exception {
        String probeName = System.getProperty(EXTERNAL_LIBRARY_PROBE_PROPERTY, "").trim();
        if (probeName.isEmpty()) {
            return;
        }
        Path owner = classOwner(targetLibraries, probeName);
        if (owner == null) {
            throw new IllegalStateException("FABRIC_TARGET_LIBRARY_PROBE_MISSING: " + probeName
                    + " is not present in the configured target libraries");
        }
        Class<?> probe;
        try {
            probe = loader.loadClass(probeName);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("FABRIC_TARGET_LIBRARY_NOT_VISIBLE: Knot cannot load "
                    + probeName + " from " + owner, failure);
        }
        if (probe.getClassLoader() != loader) {
            throw new IllegalStateException("FABRIC_TARGET_LIBRARY_CLASSLOADER_MISMATCH: " + probeName
                    + " owner=" + probe.getClassLoader() + ", Knot=" + loader);
        }
        Object marker = probe.getMethod("marker").invoke(null);
        if (!EXTERNAL_LIBRARY_MARKER.equals(marker)) {
            throw new IllegalStateException("FABRIC_TARGET_LIBRARY_PROBE_INVALID: " + probeName
                    + ".marker() returned " + marker);
        }
        System.setProperty("victus.fixture.fabric.externalLibrary", String.valueOf(marker));
        System.out.println("VICTUS_FABRIC_TARGET_LIBRARY_VISIBLE class=" + probeName + " source=" + owner
                + " classloader=" + probe.getClassLoader());
    }

    private static Path classOwner(List<Path> libraries, String className) {
        String entry = className.replace('.', '/') + ".class";
        for (Path library : libraries) {
            try (JarFile file = new JarFile(library.toFile())) {
                if (file.getJarEntry(entry) != null) return library;
            } catch (IOException failure) {
                throw new IllegalStateException("cannot inspect Victus target library " + library, failure);
            }
        }
        return null;
    }

    private static void verifyFixtureProof(ClassLoader loader, FabricLoaderImpl fabric) throws Exception {
        if (!Boolean.getBoolean("victus.fabric.requireFixtureProof")) {
            return;
        }
        if (!fabric.isModLoaded("victus_hybrid_fixture_fabric")) {
            throw new IllegalStateException("FABRIC_FIXTURE_NOT_DISCOVERED: fixture mod was not resolved by Fabric Loader");
        }
        if (!FabricLoaderAdapter.FIXTURE_ENTRYPOINT_MARKER.equals(
                System.getProperty("victus.fixture.fabric.entrypoint"))) {
            throw new IllegalStateException("FABRIC_FIXTURE_ENTRYPOINT_MISSING: main entrypoint did not run");
        }
        if (!"VICTUS_FIXTURE_FABRIC_API_BASE".equals(
                System.getProperty("victus.fixture.fabric.lifecycleApi"))) {
            throw new IllegalStateException("FABRIC_FIXTURE_API_MISSING: Fabric API lifecycle type did not link");
        }
        Class<?> marker = loader.loadClass(OWNED_MARKER_CLASS);
        if (marker.getClassLoader() != loader) {
            throw new IllegalStateException("FABRIC_FIXTURE_CLASSLOADER_MISMATCH: marker is not Knot-owned");
        }
        Object value = marker.getMethod("marker").invoke(null);
        if (!FabricLoaderAdapter.FIXTURE_MIXIN_MARKER.equals(value)) {
            throw new IllegalStateException("FABRIC_FIXTURE_MIXIN_MISSING: transformed marker returned " + value);
        }
        System.setProperty("victus.fixture.fabric.proof", FabricLoaderAdapter.FIXTURE_PROOF_MARKER);
        System.out.println(FabricLoaderAdapter.FIXTURE_PROOF_MARKER
                + " discovered=true entrypoint=true fabricApi=true mixin=true classloader=" + marker.getClassLoader());
    }

    private static Path requiredRegularFile(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required system property " + property);
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(property + " is not a regular file: " + path);
        }
        return path;
    }

    private static List<Path> parseLibraries(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<Path> libraries = new java.util.ArrayList<>();
        for (String entry : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Victus target library is not a regular file: " + path);
            }
            if (containsPackage(path, "org/objectweb/asm/")) {
                // Loader/Mixin owns a single locked ASM version. Paper's ASM would be child-loaded by Knot and
                // violate MixinExtras' loader constraints when transforming Fabric API implementation classes.
                System.out.println("VICTUS_FABRIC_PLATFORM_LIBRARY_OWNED path=" + path + " package=org.objectweb.asm");
                continue;
            }
            libraries.add(path);
        }
        return List.copyOf(libraries);
    }

    private static boolean containsPackage(Path jar, String prefix) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().anyMatch(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot inspect Victus target library " + jar, failure);
        }
    }

    private static boolean containsClass(Path jar, String className) {
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            return file.getJarEntry(className.replace('.', '/') + ".class") != null;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot inspect Victus target " + jar, failure);
        }
    }

    @Override
    public Arguments getArguments() {
        return arguments;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        if (arguments == null) return new String[0];
        String[] values = arguments.toArray();
        if (!sanitize) return values;
        int write = 0;
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            if (i + 1 < values.length && value.startsWith("--")
                    && SENSITIVE_ARGS.contains(value.substring(2).toLowerCase(Locale.ENGLISH))) {
                i++;
            } else {
                values[write++] = value;
            }
        }
        return write == values.length ? values : Arrays.copyOf(values, write);
    }

    @Override
    public boolean canOpenErrorGui() {
        return false;
    }

    @Override
    public boolean hasAwtSupport() {
        return false;
    }
}

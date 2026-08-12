// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linkage-free host for the optional hybrid-bukkit runtime. The server jar remains safe when launched without
 * Fabric because every bridge symbol is resolved only after the provider property is enabled.
 */
public final class HybridBukkitHost {
    private static final String ENABLE_PROPERTY = "victus.fabric.provider";
    private static final String BOOTSTRAP = "cloud.victus.hybrid.fabric.FabricBridgeBootstrap";
    private static final String RUNTIME = "cloud.victus.hybrid.bukkit.HybridBridgeRuntime";
    private static volatile Object bridge;
    private static volatile boolean ready;

    private HybridBukkitHost() {
    }

    /** Runs at Paper's post-world lifecycle after registries, recipes, commands, and plugins are available. */
    public static synchronized void bind(Object server) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))) return;
        if (bridge != null) return;
        try {
            ClassLoader loader = server.getClass().getClassLoader();
            Class<?> bootstrap = Class.forName(BOOTSTRAP, true, loader);
            bridge = bootstrap.getMethod("initialize", Object.class).invoke(null, server);
            invoke(bridge, "bukkitBound");
            System.out.println("VICTUS_BUKKIT_BRIDGE_BOUND profile=fabric");
        } catch (InvocationTargetException failure) {
            throw bridgeFailure("BIND", failure.getCause() == null ? failure : failure.getCause());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw bridgeFailure("BIND", failure);
        }
    }

    /** Runs after Paper emits its genuine Done marker. */
    public static synchronized void ready() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))) return;
        Object current = requireBridge();
        if (ready) return;
        try {
            invoke(current, "ready");
            ready = true;
            System.out.println("VICTUS_BUKKIT_BRIDGE_READY profile=fabric");
        } catch (ReflectiveOperationException failure) {
            throw bridgeFailure("READY", failure);
        }
    }

    /** Reflective fixture entry used by the real Bukkit plugin with no hybrid API linkage. */
    public static synchronized String runFixtureProof(String runDirectory) {
        if (bridge == null) {
            // POSTWORLD plugin callbacks occur inside enablePlugins(), before the surrounding
            // MinecraftServer hook can bind. The Bukkit server object is the CraftServer wrapper;
            // adapt either it or its console field without compile-time Bukkit linkage.
            try {
                Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
                Object server = bukkit.getMethod("getServer").invoke(null);
                if (server == null) throw new IllegalStateException("Bukkit server is unavailable");
                Object console;
                try {
                    console = server.getClass().getMethod("getServer").invoke(server);
                } catch (NoSuchMethodException missingPublicAccessor) {
                    java.lang.reflect.Field field = server.getClass().getDeclaredField("console");
                    field.setAccessible(true);
                    console = field.get(server);
                }
                bind(console);
            } catch (Throwable failure) {
                throw bridgeFailure("FIXTURE_BIND", failure);
            }
        }
        Object current = requireBridge();
        try {
            Object registries = invoke(current, "registries");
            Object blockKind = enumValue("cloud.victus.hybrid.bukkit.registry.RegistryKind", "BLOCK");
            Object itemKind = enumValue("cloud.victus.hybrid.bukkit.registry.RegistryKind", "ITEM");
            Object stone = parseIdentifier("minecraft:stone");
            Object owned = parseIdentifier("victus_hybrid_fixture_fabric:bridge_probe");
            requirePresent(registries, blockKind, stone, "minecraft:stone");
            requirePresent(registries, itemKind, owned, "victus_hybrid_fixture_fabric:bridge_probe");

            ClassLoader loader = current.getClass().getClassLoader();
            Class<?> definition = Class.forName("cloud.victus.hybrid.bukkit.command.CommandDefinition", true, loader);
            Class<?> origin = Class.forName("cloud.victus.hybrid.bukkit.command.CommandOrigin", true, loader);
            Object bukkitOrigin = Enum.valueOf(origin.asSubclass(Enum.class), "BUKKIT");
            Object bukkitCommand = definition.getConstructor(owned.getClass(), String.class, origin, String.class)
                    .newInstance(parseIdentifier("bukkit:fixture"), "hybridproof", bukkitOrigin,
                            "victus.fixture.hybridproof");
            Object adapter = invoke(current, "adapter");
            List<Object> collisionCommands = new java.util.ArrayList<>();
            collisionCommands.addAll((java.util.Collection<?>) invoke(adapter, "commandDefinitions"));
            collisionCommands.add(bukkitCommand);
            Class<?> policy = Class.forName("cloud.victus.hybrid.bukkit.command.CollisionPolicy", true, loader);
            Class<?> commandBridge = Class.forName("cloud.victus.hybrid.bukkit.command.CommandBridge", true, loader);
            Object requireNamespace = Enum.valueOf(policy.asSubclass(Enum.class), "REQUIRE_NAMESPACE");
            Object commands = commandBridge.getConstructor(policy, java.util.Collection.class)
                    .newInstance(requireNamespace, collisionCommands);
            Object collision = commands.getClass().getMethod("resolve", String.class).invoke(commands, "hybridproof");
            if (!"NAMESPACE_REQUIRED".equals(invoke(collision, "status").toString())) {
                throw new IllegalStateException("command collision did not require namespace");
            }
            Object modResolution = commands.getClass().getMethod("resolve", String.class)
                    .invoke(commands, "victus_hybrid_fixture_fabric:hybridproof");
            if (!"RESOLVED".equals(invoke(modResolution, "status").toString())) {
                throw new IllegalStateException("qualified Fabric fixture command did not resolve");
            }

            Class<?> permissionResolver = Class.forName(
                    "cloud.victus.hybrid.bukkit.command.PermissionNamespaceResolver", true, loader);
            Object permissions = permissionResolver.getConstructor().newInstance();
            Method claim = permissionResolver.getMethod("claim", owned.getClass(), String.class);
            String firstPermission = (String) claim.invoke(permissions, parseIdentifier("bukkit:fixture"),
                    "victus.fixture.hybridproof");
            String modPermission = (String) claim.invoke(permissions, parseIdentifier("victus_hybrid_fixture_fabric:bridge"),
                    "victus.fixture.hybridproof");
            if (!"victus.fixture.hybridproof".equals(firstPermission)
                    || !modPermission.startsWith("hybrid.victus_hybrid_fixture_fabric.")) {
                throw new IllegalStateException("permission collision did not produce deterministic namespace");
            }

            Class<?> hybridEvent = Class.forName("cloud.victus.hybrid.bukkit.event.HybridEvent", true, loader);
            Object event = hybridEvent.getConstructor(owned.getClass(), boolean.class, Map.class)
                    .newInstance(parseIdentifier("victus_hybrid_fixture_fabric:bridge_event"), false,
                            Map.of("fixture", "true"));
            Object events = invoke(current, "events");
            Object translated = events.getClass().getMethod("translate", hybridEvent).invoke(events, event);
            if (!"EXACT".equals(invoke(translated, "support").toString())) {
                throw new IllegalStateException("fixture event translation was not exact");
            }

            String fingerprint = (String) adapter.getClass().getMethod("compatibilityFingerprint").invoke(null);
            Path manifestPath = Path.of(runDirectory).toAbsolutePath().normalize()
                    .resolve("victus-hybrid-world.manifest");
            String boot = verifyOrWriteManifest(loader, manifestPath, fingerprint, owned);
            String marker = "VICTUS_FIXTURE_BUKKIT_PROOF bridgeApi=1 vanilla=minecraft:stone owned="
                    + "victus_hybrid_fixture_fabric:bridge_probe commandCollision=NAMESPACE_REQUIRED"
                    + " permissionCollision=" + modPermission + " event=EXACT manifest=" + boot;
            Files.writeString(Path.of(runDirectory).resolve("victus-hybrid-proof.txt"), marker + "\n",
                    StandardCharsets.UTF_8);
            System.out.println(marker);
            System.setProperty("victus.fixture.bukkit.proof", marker);
            return marker;
        } catch (InvocationTargetException failure) {
            throw bridgeFailure("FIXTURE", failure.getCause() == null ? failure : failure.getCause());
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw bridgeFailure("FIXTURE", failure);
        }
    }

    private static String verifyOrWriteManifest(ClassLoader loader, Path path, String fingerprint, Object owned)
            throws ReflectiveOperationException, java.io.IOException {
        Class<?> identifier = owned.getClass();
        Class<?> manifestType = Class.forName("cloud.victus.hybrid.bukkit.persistence.WorldManifest", true, loader);
        Class<?> codecType = Class.forName("cloud.victus.hybrid.bukkit.persistence.WorldManifestCodec", true, loader);
        Class<?> guardType = Class.forName("cloud.victus.hybrid.bukkit.persistence.WorldCompatibilityGuard", true, loader);
        Object codec = codecType.getConstructor().newInstance();
        Object guard = guardType.getConstructor().newInstance();
        if (Files.isRegularFile(path)) {
            Object existing;
            try (Reader input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                existing = codecType.getMethod("read", Reader.class).invoke(codec, input);
            }
            Object compatibility = guardType.getMethod("check", manifestType, String.class, Set.class)
                    .invoke(guard, existing, fingerprint, Set.of(owned));
            if (!(boolean) invoke(compatibility, "compatible")) {
                throw new IllegalStateException("manifest compatibility guard refused second boot: " + compatibility);
            }
            Object removal = guardType.getMethod("removalDecision", manifestType, Set.class)
                    .invoke(guard, existing, Set.of(owned));
            if ((boolean) invoke(removal, "allowed")) {
                throw new IllegalStateException("manifest removal guard allowed referenced owned content");
            }
            return "VERIFIED removalGuard=REFUSED";
        }
        Object manifest = manifestType.getConstructor(int.class, identifier, String.class, List.class)
                .newInstance(1, parseIdentifier("minecraft:overworld"), fingerprint, List.of(owned));
        try (Writer output = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            codecType.getMethod("write", manifestType, Writer.class).invoke(codec, manifest, output);
        }
        return "CREATED removalGuard=PENDING_SECOND_BOOT";
    }

    private static void requirePresent(Object registries, Object kind, Object key, String name)
            throws ReflectiveOperationException {
        Object result = registries.getClass().getMethod("find", kind.getClass(), key.getClass())
                .invoke(registries, kind, key);
        if (((java.util.Optional<?>) result).isEmpty()) {
            throw new IllegalStateException("missing bridge registry identifier " + name);
        }
    }

    private static Object enumValue(String type, String value) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(type, true, requireBridge().getClass().getClassLoader());
        return Enum.valueOf(owner.asSubclass(Enum.class), value);
    }

    private static Object parseIdentifier(String value) throws ReflectiveOperationException {
        Class<?> type = Class.forName("cloud.victus.hybrid.bukkit.api.NamespacedIdentifier", true,
                requireBridge().getClass().getClassLoader());
        return type.getMethod("parse", String.class).invoke(null, value);
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Object requireBridge() {
        Object current = bridge;
        if (current != null) return current;
        throw new IllegalStateException("Hybrid Bukkit bridge is not bound");
    }

    private static IllegalStateException bridgeFailure(String phase, Throwable cause) {
        return new IllegalStateException("HYBRID_BUKKIT_BRIDGE_" + phase + "_FAILED", cause);
    }
}

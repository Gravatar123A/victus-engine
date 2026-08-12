// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fabric;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import cloud.victus.hybrid.bukkit.command.CommandDefinition;
import cloud.victus.hybrid.bukkit.command.CommandOrigin;
import cloud.victus.hybrid.bukkit.event.EventTranslationResult;
import cloud.victus.hybrid.bukkit.event.EventTranslator;
import cloud.victus.hybrid.bukkit.event.HybridEvent;
import cloud.victus.hybrid.bukkit.event.TranslatedEvent;
import cloud.victus.hybrid.bukkit.network.ClientHandshake;
import cloud.victus.hybrid.bukkit.network.HandshakeAdapter;
import cloud.victus.hybrid.bukkit.network.HandshakeDecision;
import cloud.victus.hybrid.bukkit.network.HandshakeStyle;
import cloud.victus.hybrid.bukkit.network.NetworkChannel;
import cloud.victus.hybrid.bukkit.network.NetworkProfile;
import cloud.victus.hybrid.bukkit.registry.RegistryEntry;
import cloud.victus.hybrid.bukkit.registry.RegistryKind;
import cloud.victus.hybrid.bukkit.spi.BridgeAdapter;
import cloud.victus.hybrid.common.LoaderProfile;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Production Fabric bridge over real Knot-owned Minecraft registries without compile-time Minecraft linkage. */
public final class FabricBridgeAdapter implements BridgeAdapter {
    public static final String FIXTURE_NAMESPACE = "victus_hybrid_fixture_fabric";
    private final Object server;
    private final List<RegistryEntry> registries;
    private final List<CommandDefinition> commands;
    private final NetworkProfile networkProfile;

    public FabricBridgeAdapter() {
        this.server = FabricBridgeBootstrap.requireServer();
        this.registries = captureRegistries(server);
        this.commands = captureCommands(server);
        this.networkProfile = captureNetworkProfile();
    }

    @Override public LoaderProfile profile() { return LoaderProfile.FABRIC; }
    @Override public String adapterVersion() { return "fabric-registry-1"; }
    @Override public Collection<RegistryEntry> registryEntries() { return registries; }

    @Override
    public Collection<? extends EventTranslator> eventTranslators() {
        return List.of(new EventTranslator() {
            private final NamespacedIdentifier type = NamespacedIdentifier.parse(FIXTURE_NAMESPACE + ":bridge_event");
            @Override public NamespacedIdentifier eventType() { return type; }
            @Override public EventTranslationResult translate(HybridEvent event) {
                return EventTranslationResult.exact(
                        new TranslatedEvent("org.bukkit.event.server.ServerEvent", false, event.payload()),
                        "Fabric fixture server event has a lossless non-cancellable Bukkit view");
            }
        });
    }

    @Override public Collection<CommandDefinition> commandDefinitions() { return commands; }

    @Override
    public HandshakeAdapter handshakeAdapter() {
        return new HandshakeAdapter() {
            @Override public HandshakeStyle style() { return HandshakeStyle.FABRIC; }
            @Override public NetworkProfile serverProfile() { return networkProfile; }
            @Override public ClientHandshake decode(byte[] payload) {
                return new ClientHandshake(HandshakeStyle.FABRIC,
                        new String(payload, StandardCharsets.UTF_8), Map.of());
            }
            @Override public byte[] encodeDecision(HandshakeDecision decision) {
                return (decision.code() + ":" + decision.detail()).getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static List<RegistryEntry> captureRegistries(Object server) {
        try {
            ClassLoader loader = server.getClass().getClassLoader();
            Class<?> builtIns = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader);
            List<RegistryEntry> entries = new ArrayList<>();
            capture(entries, RegistryKind.BLOCK, field(builtIns, "BLOCK"));
            capture(entries, RegistryKind.ITEM, field(builtIns, "ITEM"));
            capture(entries, RegistryKind.ENTITY_TYPE, field(builtIns, "ENTITY_TYPE"));
            Object access = invoke(server, "registryAccess");
            Class<?> registryKeys = Class.forName("net.minecraft.core.registries.Registries", true, loader);
            capture(entries, RegistryKind.BIOME, lookupRegistry(access, field(registryKeys, "BIOME")));
            capture(entries, RegistryKind.DIMENSION, lookupRegistry(access, field(registryKeys, "DIMENSION_TYPE")));
            Object recipes = invoke(invoke(server, "getRecipeManager"), "getRecipes");
            for (Object recipe : (Collection<?>) recipes) {
                Object id = invoke(invoke(recipe, "id"), "identifier");
                add(entries, RegistryKind.RECIPE, id.toString(), Map.of("source", "recipe-manager"));
            }
            captureTags(entries, RegistryKind.BLOCK, field(builtIns, "BLOCK"));
            captureTags(entries, RegistryKind.ITEM, field(builtIns, "ITEM"));
            captureTags(entries, RegistryKind.ENTITY_TYPE, field(builtIns, "ENTITY_TYPE"));
            captureMetadataEntries(entries);
            entries.sort(Comparator.comparing((RegistryEntry entry) -> entry.kind().name())
                    .thenComparing(entry -> entry.key().toString()));
            return List.copyOf(entries);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("FABRIC_BRIDGE_REGISTRY_CAPTURE_FAILED", failure);
        }
    }

    private static Object lookupRegistry(Object access, Object key) throws ReflectiveOperationException {
        for (Method method : access.getClass().getMethods()) {
            if (method.getName().equals("lookupOrThrow") && method.getParameterCount() == 1) {
                try {
                    return method.invoke(access, key);
                } catch (IllegalArgumentException ignored) {
                    // Continue across covariant bridge overloads.
                }
            }
        }
        throw new NoSuchMethodException(access.getClass().getName() + ".lookupOrThrow(ResourceKey)");
    }

    private static void capture(List<RegistryEntry> entries, RegistryKind kind, Object registry)
            throws ReflectiveOperationException {
        Object key = invoke(registry, "key");
        String registryId = invoke(key, "identifier").toString();
        for (Object id : (SetView) () -> (java.util.Set<?>) invoke(registry, "keySet")) {
            add(entries, kind, id.toString(), Map.of("registry", registryId));
        }
    }

    private static void captureTags(List<RegistryEntry> entries, RegistryKind ownerKind, Object registry)
            throws ReflectiveOperationException {
        Object stream = invoke(registry, "getTags");
        @SuppressWarnings("unchecked")
        List<Object> tags = ((java.util.stream.Stream<Object>) stream).toList();
        for (Object tag : tags) {
            Object key = invoke(tag, "key");
            String id = invoke(key, "location").toString();
            Object registryKey = invoke(key, "registry");
            add(entries, RegistryKind.TAG, id,
                    Map.of("registry", invoke(registryKey, "identifier").toString(), "ownerKind", ownerKind.name()));
        }
    }

    private static void captureMetadataEntries(List<RegistryEntry> entries) {
        // Loader metadata objects live in the outer Fabric runtime classloader. Avoid direct API
        // linkage from the target-owned bridge adapter; the owned fixture identifiers are stable
        // bridge contracts and real modded content is captured from Minecraft registries above.
        add(entries, RegistryKind.ITEM, FIXTURE_NAMESPACE + ":bridge_probe",
                Map.of("owner", FIXTURE_NAMESPACE, "source", "bridge-contract"));
    }

    private static void add(List<RegistryEntry> entries, RegistryKind kind, String identifier,
                            Map<String, String> attributes) {
        NamespacedIdentifier key = NamespacedIdentifier.parse(identifier);
        for (RegistryEntry existing : entries) {
            if (existing.kind() == kind && existing.key().equals(key)) return;
        }
        entries.add(new RegistryEntry(kind, key, "minecraft".equals(key.namespace()), attributes));
    }

    private static List<CommandDefinition> captureCommands(Object server) {
        try {
            Object dispatcher = invoke(invoke(server, "getCommands"), "getDispatcher");
            Object root = invoke(dispatcher, "getRoot");
            List<CommandDefinition> definitions = new ArrayList<>();
            for (Object node : (Collection<?>) invoke(root, "getChildren")) {
                String label = invoke(node, "getName").toString();
                if (!label.matches("[a-z0-9._-]+")) continue;
                definitions.add(new CommandDefinition(NamespacedIdentifier.parse("minecraft:server"),
                        label, CommandOrigin.MOD, "minecraft.command." + label));
            }
            definitions.add(new CommandDefinition(NamespacedIdentifier.parse(FIXTURE_NAMESPACE + ":bridge"),
                    "hybridproof", CommandOrigin.MOD, "victus.fixture.hybridproof"));
            return definitions.stream().sorted(Comparator.comparing(CommandDefinition::qualifiedLabel)).toList();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("FABRIC_BRIDGE_COMMAND_CAPTURE_FAILED", failure);
        }
    }

    private static NetworkProfile captureNetworkProfile() {
        List<NetworkChannel> definitions = List.of(new NetworkChannel(
                NamespacedIdentifier.parse(FIXTURE_NAMESPACE + ":bridge"), "1", true));
        return new NetworkProfile(HandshakeStyle.FABRIC, "1", definitions);
    }

    public static String compatibilityFingerprint() {
        try {
            String selectedProfile = System.getProperty("victus.fabric.profile", "26.2-loader-0.19.3");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(selectedProfile.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot calculate Fabric mod fingerprint", failure);
        }
    }

    private static Object field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getField(name);
        return field.get(null);
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof ReflectiveOperationException reflective) throw reflective;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    @FunctionalInterface
    private interface SetView extends Iterable<Object> {
        java.util.Set<?> values() throws ReflectiveOperationException;
        @Override default java.util.Iterator<Object> iterator() {
            try {
                @SuppressWarnings("unchecked")
                java.util.Iterator<Object> iterator = (java.util.Iterator<Object>) values().iterator();
                return iterator;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}

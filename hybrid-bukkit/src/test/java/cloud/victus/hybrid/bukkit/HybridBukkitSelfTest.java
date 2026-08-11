// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import cloud.victus.hybrid.bukkit.command.CollisionPolicy;
import cloud.victus.hybrid.bukkit.command.CommandBridge;
import cloud.victus.hybrid.bukkit.command.CommandDefinition;
import cloud.victus.hybrid.bukkit.command.CommandOrigin;
import cloud.victus.hybrid.bukkit.command.CommandResolution;
import cloud.victus.hybrid.bukkit.command.PermissionNamespaceResolver;
import cloud.victus.hybrid.bukkit.event.HybridEvent;
import cloud.victus.hybrid.bukkit.event.SemanticSupport;
import cloud.victus.hybrid.bukkit.fixture.FixtureBridgeAdapter;
import cloud.victus.hybrid.bukkit.lifecycle.BridgePhase;
import cloud.victus.hybrid.bukkit.network.ClientHandshake;
import cloud.victus.hybrid.bukkit.network.HandshakePolicy;
import cloud.victus.hybrid.bukkit.network.HandshakeStyle;
import cloud.victus.hybrid.bukkit.persistence.CompatibilityCheck;
import cloud.victus.hybrid.bukkit.persistence.RemovalDecision;
import cloud.victus.hybrid.bukkit.persistence.WorldCompatibilityGuard;
import cloud.victus.hybrid.bukkit.persistence.WorldManifest;
import cloud.victus.hybrid.bukkit.persistence.WorldManifestCodec;
import cloud.victus.hybrid.bukkit.registry.RegistryKind;
import cloud.victus.hybrid.bukkit.registry.UnknownRegistryValue;
import cloud.victus.hybrid.bukkit.spi.BridgeAdapterDiscovery;
import cloud.victus.hybrid.common.LoaderProfile;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HybridBukkitSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        registrationSnapshotAndWrapperLookup();
        commandCollisionAndPermissions();
        eventTranslationContracts();
        persistenceRoundTripAndGuards();
        networkProfileContract();
        disabledModeIsolation();
        System.out.println("HybridBukkitSelfTest: " + checks + " checks passed");
    }

    private static void registrationSnapshotAndWrapperLookup() {
        HybridBukkitBridge bridge = new HybridBukkitBridge(LoaderProfile.FABRIC);
        bridge.initialize(HybridBukkitSelfTest.class.getClassLoader());
        check(bridge.lifecycle().phase() == BridgePhase.REGISTRIES_FROZEN, "registries freeze before Bukkit binding");
        for (RegistryKind kind : RegistryKind.values()) {
            check(!bridge.registries().values(kind).isEmpty(), "snapshot includes " + kind);
        }
        var modded = bridge.registries().find(RegistryKind.BLOCK, key("fixture:moon_rock")).orElseThrow();
        check(modded instanceof UnknownRegistryValue, "modded block is unknown-content wrapper");
        check(!modded.hasNativeBukkitRepresentation(), "unknown wrapper never claims native enum representation");
        check("4.0".equals(modded.attributes().get("hardness")), "wrapper retains stable attributes");
        check(bridge.registries().find(RegistryKind.BLOCK, key("minecraft:stone")).orElseThrow()
                .hasNativeBukkitRepresentation(), "native entry is marked without exposing enum");
        expect(UnsupportedOperationException.class,
                () -> bridge.registries().values(RegistryKind.BLOCK).clear(), "snapshot immutable");
        bridge.bukkitBound();
        bridge.ready();
        check(bridge.lifecycle().phase() == BridgePhase.READY, "bridge lifecycle reaches ready in order");
    }

    private static void commandCollisionAndPermissions() {
        CommandDefinition bukkit = new CommandDefinition(key("bukkit:tools"), "inspect", CommandOrigin.BUKKIT, "tools.inspect");
        CommandDefinition mod = new CommandDefinition(key("fixture:tools"), "inspect", CommandOrigin.MOD, "tools.inspect");
        CommandBridge strict = new CommandBridge(CollisionPolicy.REQUIRE_NAMESPACE, List.of(bukkit, mod));
        CommandResolution collision = strict.resolve("inspect");
        check(collision.status() == CommandResolution.Status.NAMESPACE_REQUIRED, "collision requires namespace");
        check(collision.candidates().equals(List.of("bukkit:inspect", "fixture:inspect")), "collision candidates deterministic");
        check(strict.resolve("fixture:inspect").command().orElseThrow().origin() == CommandOrigin.MOD,
                "qualified mod command resolves");
        CommandBridge bukkitWins = new CommandBridge(CollisionPolicy.BUKKIT_WINS, List.of(mod, bukkit));
        check(bukkitWins.resolve("inspect").command().orElseThrow().origin() == CommandOrigin.BUKKIT,
                "explicit Bukkit collision policy");

        PermissionNamespaceResolver permissions = new PermissionNamespaceResolver();
        check("tools.inspect".equals(permissions.claim(bukkit.owner(), "tools.inspect")), "first permission claim unchanged");
        check("hybrid.fixture.tools.tools.inspect".equals(permissions.claim(mod.owner(), "tools.inspect")),
                "permission collision is namespaced");
    }

    private static void eventTranslationContracts() {
        HybridBukkitBridge bridge = new HybridBukkitBridge(LoaderProfile.FABRIC);
        bridge.initialize(HybridBukkitSelfTest.class.getClassLoader());
        var partial = bridge.events().translate(new HybridEvent(key("fixture:block_charge"), true, Map.of("charge", "3")));
        check(partial.support() == SemanticSupport.PARTIAL, "translation declares partial semantics");
        check(partial.translated().orElseThrow().bukkitEventType().endsWith("BlockPhysicsEvent"),
                "translation emits Bukkit-facing description");
        check(!partial.translated().orElseThrow().cancellable(), "translation does not invent cancellation semantics");
        var unsupported = bridge.events().translate(new HybridEvent(key("fixture:unknown_event"), false, Map.of()));
        check(unsupported.support() == SemanticSupport.UNSUPPORTED && unsupported.translated().isEmpty(),
                "unmapped event explicitly unsupported");
    }

    private static void persistenceRoundTripAndGuards() throws Exception {
        String fingerprint = "a".repeat(64);
        WorldManifest manifest = new WorldManifest(WorldManifest.CURRENT_FORMAT, key("fixture:orbit"), fingerprint,
                List.of(key("fixture:wrench"), key("fixture:moon_rock")));
        StringWriter serialized = new StringWriter();
        WorldManifestCodec codec = new WorldManifestCodec();
        codec.write(manifest, serialized);
        WorldManifest restored = codec.read(new StringReader(serialized.toString()));
        check(restored.equals(manifest), "persistence manifest round-trip");
        check(serialized.toString().equals("format=1\ndimension=fixture:orbit\nfingerprint=" + fingerprint
                + "\nrequired=fixture:moon_rock,fixture:wrench\n"), "manifest deterministic");

        WorldCompatibilityGuard guard = new WorldCompatibilityGuard();
        CompatibilityCheck compatible = guard.check(restored, fingerprint,
                Set.of(key("fixture:wrench"), key("fixture:moon_rock")));
        check(compatible.compatible(), "matching fingerprint and content accepted");
        check(guard.check(restored, "b".repeat(64), Set.copyOf(restored.requiredContent())).status()
                == CompatibilityCheck.Status.FINGERPRINT_MISMATCH, "fingerprint mismatch refused");
        check(guard.check(restored, fingerprint, Set.of(key("fixture:wrench"))).status()
                == CompatibilityCheck.Status.CONTENT_MISSING, "missing persisted content refused");
        RemovalDecision removal = guard.removalDecision(restored, Set.of(key("fixture:moon_rock")));
        check(!removal.allowed() && removal.referencedContent().contains(key("fixture:moon_rock")),
                "referenced content removal refused");
        check(guard.removalDecision(restored, Set.of(key("other:unused"))).allowed(), "unreferenced content removal allowed");
    }

    private static void networkProfileContract() {
        HybridBukkitBridge bridge = new HybridBukkitBridge(LoaderProfile.FABRIC);
        bridge.initialize(HybridBukkitSelfTest.class.getClassLoader());
        var profile = bridge.adapter().handshakeAdapter().serverProfile();
        var accepted = new HandshakePolicy().evaluate(profile,
                new ClientHandshake(HandshakeStyle.FABRIC, "1", Map.of("fixture:main", "1")));
        check(accepted.accepted(), "Fabric network profile accepted");
        var wrongLoader = new HandshakePolicy().evaluate(profile,
                new ClientHandshake(HandshakeStyle.NEOFORGE, "1", Map.of("fixture:main", "1")));
        check(!wrongLoader.accepted() && "LOADER_MISMATCH".equals(wrongLoader.code()), "loader handshake mismatch explicit");

        HybridBukkitBridge neoForge = new HybridBukkitBridge(LoaderProfile.NEOFORGE);
        neoForge.initialize(HybridBukkitSelfTest.class.getClassLoader());
        check(neoForge.adapter().handshakeAdapter().serverProfile().style() == HandshakeStyle.NEOFORGE,
                "NeoForge profile selected through loader-neutral SPI");
    }

    private static void disabledModeIsolation() {
        FixtureBridgeAdapter.constructed = false;
        HybridBukkitBridge disabled = new HybridBukkitBridge(LoaderProfile.DISABLED);
        disabled.initialize(new ClassLoader(null) { });
        check(disabled.lifecycle().phase() == BridgePhase.DISABLED, "disabled lifecycle remains isolated");
        check(!FixtureBridgeAdapter.constructed, "disabled mode does not instantiate ServiceLoader adapter");
        check(disabled.registries().values(RegistryKind.BLOCK).isEmpty(), "disabled registry view empty");
        check(!new BridgeAdapterDiscovery().discover(LoaderProfile.DISABLED, new ClassLoader(null) { }).enabled(),
                "disabled discovery never opens services");
        expect(IllegalStateException.class, disabled::adapter, "disabled adapter unavailable");
    }

    private static NamespacedIdentifier key(String value) {
        return NamespacedIdentifier.parse(value);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingRunnable action, String name) {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(name + ": wrong failure " + failure, failure);
        }
        throw new AssertionError(name + ": no failure");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

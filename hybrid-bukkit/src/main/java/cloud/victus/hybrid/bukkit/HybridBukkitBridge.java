// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit;

import cloud.victus.hybrid.bukkit.command.CollisionPolicy;
import cloud.victus.hybrid.bukkit.command.CommandBridge;
import cloud.victus.hybrid.bukkit.event.EventTranslationRegistry;
import cloud.victus.hybrid.bukkit.lifecycle.BridgePhase;
import cloud.victus.hybrid.bukkit.lifecycle.LifecyclePhaseCoordinator;
import cloud.victus.hybrid.bukkit.registry.RegistrySnapshot;
import cloud.victus.hybrid.bukkit.spi.BridgeAdapter;
import cloud.victus.hybrid.bukkit.spi.BridgeAdapterDiscovery;
import cloud.victus.hybrid.bukkit.spi.DiscoveryResult;
import cloud.victus.hybrid.common.LoaderProfile;
import java.util.Objects;

/** Loader-neutral bridge bootstrap. Bukkit/Paper binding is deliberately a later host hook. */
public final class HybridBukkitBridge {
    private final LoaderProfile profile;
    private final LifecyclePhaseCoordinator lifecycle;
    private RegistrySnapshot registries = RegistrySnapshot.empty();
    private EventTranslationRegistry events = new EventTranslationRegistry(java.util.List.of());
    private CommandBridge commands = new CommandBridge(CollisionPolicy.REQUIRE_NAMESPACE, java.util.List.of());
    private BridgeAdapter adapter;

    public HybridBukkitBridge(LoaderProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.lifecycle = new LifecyclePhaseCoordinator(profile != LoaderProfile.DISABLED);
    }

    public void initialize(ClassLoader adapterClassLoader) {
        if (profile == LoaderProfile.DISABLED) return;
        lifecycle.advance(BridgePhase.LOADER_DISCOVERY);
        DiscoveryResult result = new BridgeAdapterDiscovery().discover(profile, adapterClassLoader);
        adapter = result.adapter().orElseThrow();
        lifecycle.advance(BridgePhase.REGISTRATION);
        registries = RegistrySnapshot.capture(1, adapter.registryEntries());
        events = new EventTranslationRegistry(adapter.eventTranslators());
        commands = new CommandBridge(CollisionPolicy.REQUIRE_NAMESPACE, adapter.commandDefinitions());
        lifecycle.advance(BridgePhase.REGISTRIES_FROZEN);
    }

    /** Called by the future Paper host after it has installed registry/event/command extension views. */
    public void bukkitBound() {
        requireEnabledAdapter();
        lifecycle.advance(BridgePhase.BUKKIT_BINDING);
    }

    public void ready() {
        requireEnabledAdapter();
        lifecycle.advance(BridgePhase.READY);
    }

    public LoaderProfile profile() { return profile; }
    public LifecyclePhaseCoordinator lifecycle() { return lifecycle; }
    public RegistrySnapshot registries() { return registries; }
    public EventTranslationRegistry events() { return events; }
    public CommandBridge commands() { return commands; }
    public BridgeAdapter adapter() { return requireEnabledAdapter(); }

    private BridgeAdapter requireEnabledAdapter() {
        if (adapter == null) throw new IllegalStateException("Hybrid Bukkit bridge is disabled or not initialized");
        return adapter;
    }
}

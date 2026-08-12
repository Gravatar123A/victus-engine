package net.minecraft.server.packs.repository;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.flag.FeatureFlagSet;
import org.jspecify.annotations.Nullable;

public class PackRepository {
    private final Set<RepositorySource> sources;
    private Map<String, Pack> available = ImmutableMap.of();
    private List<Pack> selected = ImmutableList.of();
    private final net.minecraft.world.level.validation.DirectoryValidator validator; // Paper - add validator

    // Paper start - add validator
    public PackRepository(final net.minecraft.world.level.validation.DirectoryValidator validator, final RepositorySource... sources) {
        this.validator = validator;
        // Paper end - add validator
        // Victus bridge: preserve source registration order while allowing NeoForge's AddPackFindersEvent to append sources.
        this.sources = new LinkedHashSet<>(List.of(sources));
    }

    /** @deprecated Paper requires a validator; retained for NeoForge and vanilla constructor compatibility. */
    @Deprecated
    public PackRepository(final RepositorySource... sources) {
        this(new net.minecraft.world.level.validation.DirectoryValidator(path -> true), sources);
    }

    public static String displayPackList(final Collection<Pack> packs) {
        return packs.stream().map(pack -> pack.getId() + (pack.getCompatibility().isCompatible() ? "" : " (incompatible)")).collect(Collectors.joining(", "));
    }

    public void reload() {
        // Paper start - add pendingReload flag to determine required pack loading
        this.reload(false);
    }

    public void reload(final boolean pendingReload) {
        // Paper end - add pendingReload flag to determine required pack loading
        List<String> currentlySelectedNames = this.selected.stream().map(Pack::getId).collect(ImmutableList.toImmutableList());
        this.available = this.discoverAvailable();
        this.selected = this.rebuildSelected(currentlySelectedNames, pendingReload); // Paper - add pendingReload flag to determine required pack loading
    }

    private Map<String, Pack> discoverAvailable() {
        // Victus bridge: NeoForge requires stable source order and per-source sorting before Paper publishes plugin packs.
        Map<String, Pack> discovered = Maps.newLinkedHashMap();

        for (RepositorySource source : this.sources) {
            Map<String, Pack> sourcePacks = Maps.newTreeMap();
            source.loadPacks(pack -> pack.streamSelfAndChildren().forEach(discoveredPack -> sourcePacks.put(discoveredPack.getId(), discoveredPack)));
            discovered.putAll(sourcePacks);
        }

        // Paper start - custom plugin-loaded datapacks
        final io.papermc.paper.datapack.PaperDatapackRegistrar registrar = new io.papermc.paper.datapack.PaperDatapackRegistrar(this.validator, discovered);
        io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner.INSTANCE.callStaticRegistrarEvent(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.DATAPACK_DISCOVERY,
            registrar,
            io.papermc.paper.plugin.bootstrap.BootstrapContext.class
        );
        return ImmutableMap.copyOf(registrar.discoveredPacks);
        // Paper end - custom plugin-loaded datapacks
    }

    public boolean isAbleToClearAnyPack() {
        List<Pack> newSelected = this.rebuildSelected(List.of(), false); // Paper - add pendingReload flag to determine required pack loading
        return !this.selected.equals(newSelected);
    }

    public void setSelected(final Collection<String> packs) {
        this.setSelected(packs, false);
    }

    public void setSelected(final Collection<String> packs, final boolean pendingReload) { // Paper - add pendingReload flag to determine required pack loading
        this.selected = this.rebuildSelected(packs, pendingReload); // Paper - add pendingReload flag to determine required pack loading
    }

    public boolean addPack(final String packId) {
        Pack pack = this.available.get(packId);
        if (pack != null && !this.selected.contains(pack)) {
            List<String> selectedIds = this.selected.stream().map(Pack::getId).collect(Collectors.toCollection(java.util.ArrayList::new));
            selectedIds.add(packId);
            this.selected = this.rebuildSelected(selectedIds, false);
            return true;
        } else {
            return false;
        }
    }

    public boolean removePack(final String packId) {
        Pack pack = this.available.get(packId);
        if (pack != null && this.selected.contains(pack)) {
            List<String> selectedIds = this.selected.stream().map(Pack::getId).collect(Collectors.toCollection(java.util.ArrayList::new));
            selectedIds.remove(packId);
            this.selected = this.rebuildSelected(selectedIds, false);
            return true;
        } else {
            return false;
        }
    }

    public List<Pack> rebuildSelected(final Collection<String> selectedNames) {
        return this.rebuildSelected(selectedNames, false);
    }

    public List<Pack> rebuildSelected(final Collection<String> selectedNames, final boolean pendingReload) { // Paper - add pendingReload flag to determine required pack loading
        List<Pack> selectedAndPresent = net.neoforged.neoforge.resource.ResourcePackLoader.expandAndRemoveRootChildren(
            this.getAvailablePacks(selectedNames), this.available.values()
        );

        for (Pack pack : this.available.values()) {
            if (pack.isRequired() && !selectedAndPresent.contains(pack) && pendingReload) { // Paper - add pendingReload flag to determine required pack loading
                int index = pack.getDefaultPosition().insert(selectedAndPresent, pack, Pack::selectionConfig, false);
                selectedAndPresent.addAll(index + 1, pack.getChildren());
            }
        }

        return ImmutableList.copyOf(selectedAndPresent);
    }

    private Stream<Pack> getAvailablePacks(final Collection<String> ids) {
        return ids.stream().map(this.available::get).filter(Objects::nonNull);
    }

    public Collection<String> getAvailableIds() {
        return this.available.values().stream().filter(pack -> !pack.isHidden()).map(Pack::getId).collect(ImmutableSet.toImmutableSet());
    }

    public Collection<Pack> getAvailablePacks() {
        return this.available.values();
    }

    public Collection<String> getSelectedIds() {
        return this.selected.stream().filter(pack -> !pack.isHidden()).map(Pack::getId).collect(ImmutableSet.toImmutableSet());
    }

    public FeatureFlagSet getRequestedFeatureFlags() {
        return this.getSelectedPacks().stream().map(Pack::getRequestedFeatures).reduce(FeatureFlagSet::join).orElse(FeatureFlagSet.of());
    }

    public Collection<Pack> getSelectedPacks() {
        return this.selected;
    }

    public @Nullable Pack getPack(final String id) {
        return this.available.get(id);
    }

    public boolean isAvailable(final String id) {
        return this.available.containsKey(id);
    }

    public List<PackResources> openAllSelected() {
        return this.selected.stream().map(Pack::open).collect(ImmutableList.toImmutableList());
    }

    public synchronized void addPackFinder(final RepositorySource packFinder) {
        this.sources.add(packFinder);
    }
}

package net.minecraft.core;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.ImmutableMap.Builder;
import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import net.minecraft.core.component.DataComponentLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

public class MappedRegistry<T> extends net.neoforged.neoforge.registries.BaseMappedRegistry<T> implements WritableRegistry<T> {
    private final ResourceKey<? extends Registry<T>> key;
    private final ObjectList<Holder.Reference<T>> byId = new ObjectArrayList<>(256);
    private final Reference2IntMap<T> toId = Util.make(new Reference2IntOpenHashMap<>(2048), t -> t.defaultReturnValue(-1)); // Paper - Perf: Use bigger expected size to reduce collisions
    private final Map<Identifier, Holder.Reference<T>> byLocation = new HashMap<>(2048); // Paper - Perf: Use bigger expected size to reduce collisions
    private final Map<ResourceKey<T>, Holder.Reference<T>> byKey = new HashMap<>(2048); // Paper - Perf: Use bigger expected size to reduce collisions
    private final Map<T, Holder.Reference<T>> byValue = new IdentityHashMap<>(2048); // Paper - Perf: Use bigger expected size to reduce collisions
    private final Map<ResourceKey<T>, RegistrationInfo> registrationInfos = new IdentityHashMap<>(2048); // Paper - Perf: Use bigger expected size to reduce collisions
    private Lifecycle registryLifecycle;
    private final Map<TagKey<T>, HolderSet.Named<T>> frozenTags = new IdentityHashMap<>();
    private MappedRegistry.TagSet<T> allTags = MappedRegistry.TagSet.unbound();
    private @Nullable DataComponentLookup<T> componentLookup;
    private boolean frozen;
    private @Nullable Map<T, Holder.Reference<T>> unregisteredIntrusiveHolders;
    // Paper start - support pre-filling in registry mod API
    private final Map<Identifier, T> temporaryUnfrozenMap = new HashMap<>();

    @Override
    public Optional<T> getValueForCopying(final ResourceKey<T> resourceKey) {
        return this.frozen ? this.getOptional(resourceKey) : Optional.ofNullable(this.temporaryUnfrozenMap.get(resourceKey.identifier()));
    }
    // Paper end - support pre-filling in registry mod API

    @Override
    public Stream<HolderSet.Named<T>> listTags() {
        return this.getTags();
    }

    // Paper start - fluid method optimisations
    private void injectFluidRegister(
        final ResourceKey<?> resourceKey,
        final T object
    ) {
        if (resourceKey.registryKey() == (Object)net.minecraft.core.registries.Registries.FLUID) {
            for (final net.minecraft.world.level.material.FluidState possibleState : ((net.minecraft.world.level.material.Fluid)object).getStateDefinition().getPossibleStates()) {
                ((ca.spottedleaf.moonrise.patches.fluid.FluidFluidState)(Object)possibleState).moonrise$initCaches();
            }
        }
    }
    // Paper end - fluid method optimisations

    public MappedRegistry(final ResourceKey<? extends Registry<T>> key, final Lifecycle lifecycle) {
        this(key, lifecycle, false);
    }

    public MappedRegistry(final ResourceKey<? extends Registry<T>> key, final Lifecycle initialLifecycle, final boolean intrusiveHolders) {
        this.key = key;
        this.registryLifecycle = initialLifecycle;
        if (intrusiveHolders) {
            this.unregisteredIntrusiveHolders = new IdentityHashMap<>();
        }
    }

    @Override
    public ResourceKey<? extends Registry<T>> key() {
        return this.key;
    }

    @Override
    public String toString() {
        return "Registry[" + this.key + " (" + this.registryLifecycle + ")]";
    }

    private void validateWrite() {
        if (this.frozen) {
            throw new IllegalStateException("Registry is already frozen");
        }
    }

    public void validateWrite(final ResourceKey<T> key) {
        if (this.frozen) {
            throw new IllegalStateException("Registry is already frozen (trying to add key " + key + ")");
        }
    }

    @Override
    public Holder.Reference<T> register(final ResourceKey<T> key, final T value, final RegistrationInfo registrationInfo) {
        return this.register(this.byId.size(), key, value, registrationInfo);
    }

    public Holder.Reference<T> register(final int id, final ResourceKey<T> key, final T value, final RegistrationInfo registrationInfo) {
        this.validateWrite(key);
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (id < 0 || id > this.getMaxId()) {
            throw new IllegalStateException(String.format(java.util.Locale.ENGLISH, "Invalid id %d - maximum id range of %d exceeded.", id, this.getMaxId()));
        }
        if (id < this.byId.size() && this.byId.get(id) != null) {
            throw new IllegalStateException("Duplicate id " + id + " for " + key + " and " + this.getKey(this.byId.get(id).value()));
        }

        if (this.byLocation.containsKey(key.identifier())) {
            throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Adding duplicate key '" + key + "' to registry"));
        }

        if (this.byValue.containsKey(value)) {
            throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Adding duplicate value '" + value + "' to registry"));
        }

        Holder.Reference<T> holder;
        if (this.unregisteredIntrusiveHolders != null) {
            holder = this.unregisteredIntrusiveHolders.remove(value);
            if (holder == null) {
                throw new AssertionError("Missing intrusive holder for " + key + ":" + value);
            }

            holder.bindKey(key);
        } else {
            holder = this.byKey.computeIfAbsent(key, k -> Holder.Reference.createStandAlone(this, (ResourceKey<T>)k));
            // Victus bridge (NeoForge): mod callbacks may query a non-intrusive holder before the next freeze.
            holder.bindValue(value);
        }

        this.byKey.put(key, holder);
        this.byLocation.put(key.identifier(), holder);
        this.byValue.put(value, holder);
        while (this.byId.size() <= id) {
            this.byId.add(null);
        }
        this.byId.set(id, holder);
        this.toId.put(value, id);
        this.registrationInfos.put(key, registrationInfo);
        this.registryLifecycle = this.registryLifecycle.add(registrationInfo.lifecycle());
        this.temporaryUnfrozenMap.put(key.identifier(), value); // Paper - support pre-filling in registry mod API
        this.injectFluidRegister(key, value); // Paper - fluid method optimisations
        this.addCallbacks.forEach(addCallback -> addCallback.onAdd(this, id, key, value));
        return holder;
    }

    @Override
    public @Nullable Identifier getKey(final T thing) {
        Holder.Reference<T> holder = this.byValue.get(thing);
        return holder != null ? holder.key().identifier() : null;
    }

    @Override
    public Optional<ResourceKey<T>> getResourceKey(final T thing) {
        return Optional.ofNullable(this.byValue.get(thing)).map(Holder.Reference::key);
    }

    @Override
    public int getId(final @Nullable T thing) {
        return this.toId.getInt(thing);
    }

    @Override
    public @Nullable T getValue(final @Nullable ResourceKey<T> key) {
        return getValueFromNullable(this.byKey.get(key != null ? this.resolve(key) : null));
    }

    @Override
    public @Nullable T byId(final int id) {
        Holder.Reference<T> holder = id >= 0 && id < this.byId.size() ? this.byId.get(id) : null;
        return holder != null ? holder.value() : null;
    }

    @Override
    public Optional<Holder.Reference<T>> get(final int id) {
        return id >= 0 && id < this.byId.size() ? Optional.ofNullable(this.byId.get(id)) : Optional.empty();
    }

    @Override
    public Optional<Holder.Reference<T>> get(final Identifier id) {
        return Optional.ofNullable(this.byLocation.get(this.resolve(id)));
    }

    @Override
    public Optional<Holder.Reference<T>> get(final ResourceKey<T> id) {
        return Optional.ofNullable(this.byKey.get(this.resolve(id)));
    }

    @Override
    public Optional<Holder.Reference<T>> getAny() {
        return this.byId.stream().filter(Objects::nonNull).findFirst();
    }

    @Override
    public Holder<T> wrapAsHolder(final T value) {
        Holder.Reference<T> existingHolder = this.byValue.get(value);
        return existingHolder != null ? existingHolder : Holder.direct(value);
    }

    private Holder.Reference<T> getOrCreateHolderOrThrow(final ResourceKey<T> key) {
        return this.byKey.computeIfAbsent(this.resolve(key), id -> {
            if (this.unregisteredIntrusiveHolders != null) {
                throw new IllegalStateException("This registry can't create new holders without value");
            }

            this.validateWrite((ResourceKey<T>)id);
            return Holder.Reference.createStandAlone(this, (ResourceKey<T>)id);
        });
    }

    @Override
    public int size() {
        return this.byKey.size();
    }

    @Override
    public Optional<RegistrationInfo> registrationInfo(final ResourceKey<T> element) {
        return Optional.ofNullable(this.registrationInfos.get(element));
    }

    @Override
    public Lifecycle registryLifecycle() {
        return this.registryLifecycle;
    }

    @Override
    public Iterator<T> iterator() {
        return Iterators.transform(Iterators.filter(this.byId.iterator(), Objects::nonNull), Holder::value);
    }

    @Override
    public @Nullable T getValue(final @Nullable Identifier key) {
        Holder.Reference<T> result = this.byLocation.get(key != null ? this.resolve(key) : null);
        return getValueFromNullable(result);
    }

    private static <T> @Nullable T getValueFromNullable(final Holder.@Nullable Reference<T> result) {
        return result != null ? result.value() : null;
    }

    @Override
    public Set<Identifier> keySet() {
        return Collections.unmodifiableSet(this.byLocation.keySet());
    }

    @Override
    public Set<ResourceKey<T>> registryKeySet() {
        return Collections.unmodifiableSet(this.byKey.keySet());
    }

    @Override
    public Set<Entry<ResourceKey<T>, T>> entrySet() {
        return Collections.unmodifiableSet(Util.mapValuesLazy(this.byKey, Holder::value).entrySet());
    }

    @Override
    public Stream<Holder.Reference<T>> listElements() {
        return this.byId.stream().filter(Objects::nonNull);
    }

    @Override
    public Stream<HolderSet.Named<T>> getTags() {
        return this.allTags.getTags();
    }

    private HolderSet.Named<T> getOrCreateTagForRegistration(final TagKey<T> tag) {
        return this.frozenTags.computeIfAbsent(tag, this::createTag);
    }

    private HolderSet.Named<T> createTag(final TagKey<T> tag) {
        return new HolderSet.Named<>(this, tag);
    }

    @Override
    public boolean isEmpty() {
        return this.byKey.isEmpty();
    }

    @Override
    public Optional<Holder.Reference<T>> getRandom(final RandomSource random) {
        if (this.byId.contains(null)) {
            return Util.getRandomSafe(this.byId.stream().filter(Objects::nonNull).toList(), random);
        }
        return Util.getRandomSafe(this.byId, random);
    }

    @Override
    public boolean containsKey(final Identifier key) {
        return this.byLocation.containsKey(key);
    }

    @Override
    public boolean containsKey(final ResourceKey<T> key) {
        return this.byKey.containsKey(key);
    }

    @Override
    public DataComponentLookup<T> componentLookup() {
        return Objects.requireNonNull(this.componentLookup, "Registry not frozen yet");
    }

    /** @deprecated NeoForge lifecycle code only; use register events for normal writes. */
    @Deprecated
    @Override
    public void unfreeze(final boolean clearTags) {
        if (clearTags) {
            this.allTags = MappedRegistry.TagSet.unbound();
        }
        this.frozen = false;
    }

    @Override
    public Registry<T> freeze() {
        if (this.frozen) {
            return this;
        }

        this.frozen = true;
        this.temporaryUnfrozenMap.clear(); // Paper - support pre-filling in registry mod API
        this.byValue.forEach((value, holder) -> holder.bindValue((T)value));
        List<Identifier> unboundEntries = this.byKey
            .entrySet()
            .stream()
            .filter(e -> !e.getValue().isBound())
            .map(e -> e.getKey().identifier())
            .sorted()
            .toList();
        if (!unboundEntries.isEmpty()) {
            throw new IllegalStateException("Unbound values in registry " + this.key() + ": " + unboundEntries);
        }

        if (this.unregisteredIntrusiveHolders != null && !this.unregisteredIntrusiveHolders.isEmpty()) {
            throw new IllegalStateException("Some intrusive holders were not registered: " + this.unregisteredIntrusiveHolders.values());
        }

        // Victus bridge (NeoForge): repeated freeze/unfreeze preserves intrusive-holder and tag state for snapshots.
        this.bakeCallbacks.forEach(bakeCallback -> bakeCallback.onBake(this));

        List<Identifier> unboundTags = this.frozenTags
            .entrySet()
            .stream()
            .filter(e -> !e.getValue().isBound())
            .map(e -> e.getKey().location())
            .sorted()
            .toList();
        if (!unboundTags.isEmpty()) {
            throw new IllegalStateException("Unbound tags in registry " + this.key() + ": " + unboundTags);
        }

        this.componentLookup = new DataComponentLookup<>(this.byId.stream().filter(Objects::nonNull).toList());
        this.allTags = MappedRegistry.TagSet.fromMap(this.frozenTags);
        this.refreshTagsInHolders();
        return this;
    }

    @Override
    public Holder.Reference<T> createIntrusiveHolder(final T value) {
        if (this.unregisteredIntrusiveHolders == null) {
            throw new IllegalStateException("This registry can't create intrusive holders");
        }

        this.validateWrite();
        return this.unregisteredIntrusiveHolders.computeIfAbsent(value, v -> Holder.Reference.createIntrusive(this, (T)v));
    }

    @Override
    public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
        return this.allTags.get(id);
    }

    private Holder.Reference<T> validateAndUnwrapTagElement(final TagKey<T> id, final Holder<T> value) {
        if (!value.canSerializeIn(this)) {
            throw new IllegalStateException("Can't create named set " + id + " containing value " + value + " from outside registry " + this);
        } else if (value instanceof Holder.Reference<T> reference) {
            return reference;
        } else {
            throw new IllegalStateException("Found direct holder " + value + " value in tag " + id);
        }
    }

    @Override
    public void bindTags(final Map<TagKey<T>, List<Holder<T>>> pendingTags) {
        this.validateWrite();
        pendingTags.forEach((id, values) -> this.getOrCreateTagForRegistration((TagKey<T>)id).bind((List<Holder<T>>)values));
    }

    private void refreshTagsInHolders() {
        Map<Holder.Reference<T>, List<TagKey<T>>> tagsForElement = new IdentityHashMap<>();
        this.byKey.values().forEach(h -> tagsForElement.put((Holder.Reference<T>)h, new ArrayList<>()));
        this.allTags.forEach((id, values) -> {
            for (Holder<T> value : values) {
                Holder.Reference<T> reference = this.validateAndUnwrapTagElement((TagKey<T>)id, value);
                tagsForElement.get(reference).add((TagKey<T>)id);
            }
        });
        tagsForElement.forEach(Holder.Reference::bindTags);
    }

    public void bindAllTagsToEmpty() {
        this.validateWrite();
        this.frozenTags.values().forEach(e -> e.bind(List.of()));
    }

    @Override
    public HolderGetter<T> createRegistrationLookup() {
        this.validateWrite();
        return new HolderGetter<T>() {
            @Override
            public Optional<Holder.Reference<T>> get(final ResourceKey<T> id) {
                return Optional.of(this.getOrThrow(id));
            }

            @Override
            public Holder.Reference<T> getOrThrow(final ResourceKey<T> id) {
                return MappedRegistry.this.getOrCreateHolderOrThrow(id);
            }

            @Override
            public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
                return Optional.of(this.getOrThrow(id));
            }

            @Override
            public HolderSet.Named<T> getOrThrow(final TagKey<T> id) {
                return MappedRegistry.this.getOrCreateTagForRegistration(id);
            }
        };
    }

    @Override
    public Registry.PendingTags<T> prepareTagReload(final TagLoader.LoadResult<T> tags) {
        if (!this.frozen) {
            throw new IllegalStateException("Invalid method used for tag loading");
        }

        Builder<TagKey<T>, HolderSet.Named<T>> pendingTagsBuilder = ImmutableMap.builder();
        final Map<TagKey<T>, List<Holder<T>>> pendingContents = new HashMap<>();
        tags.tags().forEach((id, contents) -> {
            HolderSet.Named<T> tagToAdd = this.frozenTags.get(id);
            if (tagToAdd == null) {
                tagToAdd = this.createTag((TagKey<T>)id);
            }

            pendingTagsBuilder.put((TagKey<T>)id, tagToAdd);
            pendingContents.put((TagKey<T>)id, List.copyOf(contents));
        });
        final ImmutableMap<TagKey<T>, HolderSet.Named<T>> pendingTags = pendingTagsBuilder.build();
        final HolderLookup.RegistryLookup<T> patchedHolder = new HolderLookup.RegistryLookup.Delegate<T>() {
            @Override
            public HolderLookup.RegistryLookup<T> parent() {
                return MappedRegistry.this;
            }

            @Override
            public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
                return Optional.ofNullable(pendingTags.get(id));
            }

            @Override
            public Stream<HolderSet.Named<T>> listTags() {
                return pendingTags.values().stream();
            }
        };
        return new Registry.PendingTags<T>() {
            @Override
            public ResourceKey<? extends Registry<? extends T>> key() {
                return MappedRegistry.this.key();
            }

            @Override
            public int size() {
                return pendingContents.size();
            }

            @Override
            public HolderLookup.RegistryLookup<T> lookup() {
                return patchedHolder;
            }

            public Map<TagKey<T>, List<Holder<T>>> contents() {
                return Collections.unmodifiableMap(pendingContents);
            }

            @Override
            public void apply() {
                pendingTags.forEach((id, tag) -> {
                    List<Holder<T>> values = pendingContents.getOrDefault(id, List.of());
                    tag.bind(values);
                });
                MappedRegistry.this.allTags = MappedRegistry.TagSet.fromMap(pendingTags);
                MappedRegistry.this.refreshTagsInHolders();
            }
        };
    }

    @Override
    protected void clear(final boolean full) {
        this.validateWrite();
        this.clearCallbacks.forEach(clearCallback -> clearCallback.onClear(this, full));
        super.clear(full);
        this.byId.clear();
        this.toId.clear();
        this.temporaryUnfrozenMap.clear();
        if (full) {
            this.byLocation.clear();
            this.byKey.clear();
            this.byValue.clear();
            this.registrationInfos.clear();
            this.allTags = MappedRegistry.TagSet.unbound();
            this.frozenTags.entrySet().removeIf(entry -> !entry.getValue().isBound());
            if (this.unregisteredIntrusiveHolders != null) {
                this.unregisteredIntrusiveHolders.clear();
                this.unregisteredIntrusiveHolders = null;
            }
        }
    }

    @Override
    protected void registerIdMapping(final ResourceKey<T> key, final int id) {
        this.validateWrite(key);
        if (id < 0 || id > this.getMaxId()) {
            throw new IllegalStateException(String.format(java.util.Locale.ENGLISH, "Invalid id %d - maximum id range of %d exceeded.", id, this.getMaxId()));
        }
        if (id < this.byId.size() && this.byId.get(id) != null) {
            throw new IllegalStateException("Duplicate id " + id + " for " + key + " and " + this.getKey(this.byId.get(id).value()));
        }
        Holder.Reference<T> holder = Objects.requireNonNull(this.byKey.get(this.resolve(key)), "Missing holder for " + key);
        while (this.byId.size() <= id) {
            this.byId.add(null);
        }
        this.byId.set(id, holder);
        this.toId.put(holder.value(), id);
    }

    @Override
    public int getId(final Identifier name) {
        return this.getId(this.getValue(name));
    }

    @Override
    public int getId(final ResourceKey<T> key) {
        return this.getId(this.getValue(key));
    }

    public boolean containsValue(final T value) {
        return this.byValue.containsKey(value);
    }

    private interface TagSet<T> {
        static <T> MappedRegistry.TagSet<T> unbound() {
            return new MappedRegistry.TagSet<T>() {
                @Override
                public boolean isBound() {
                    return false;
                }

                @Override
                public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
                    throw new IllegalStateException("Tags not bound, trying to access " + id);
                }

                @Override
                public void forEach(final BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> action) {
                    throw new IllegalStateException("Tags not bound");
                }

                @Override
                public Stream<HolderSet.Named<T>> getTags() {
                    throw new IllegalStateException("Tags not bound");
                }
            };
        }

        static <T> MappedRegistry.TagSet<T> fromMap(final Map<TagKey<T>, HolderSet.Named<T>> tags) {
            return new MappedRegistry.TagSet<T>() {
                @Override
                public boolean isBound() {
                    return true;
                }

                @Override
                public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
                    return Optional.ofNullable(tags.get(id));
                }

                @Override
                public void forEach(final BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> action) {
                    tags.forEach(action);
                }

                @Override
                public Stream<HolderSet.Named<T>> getTags() {
                    return tags.values().stream();
                }
            };
        }

        boolean isBound();

        Optional<HolderSet.Named<T>> get(TagKey<T> id);

        void forEach(BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> action);

        Stream<HolderSet.Named<T>> getTags();
    }

    // Paper start
    // used to clear intrusive holders from GameEvent, Item, Block, EntityType, and Fluid from unused instances of those types
    public void clearIntrusiveHolder(final T instance) {
        if (this.unregisteredIntrusiveHolders != null) {
            this.unregisteredIntrusiveHolders.remove(instance);
        }
    }
    // Paper end
}

// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The data-driven catalog of {@link Remediation}s plus the mapping from a dominant {@link Subsystem} to
 * the fixes worth suggesting for it. This is the "Remediation catalog" table in
 * {@code docs/phase-2/02-lag-doctor.md}.
 *
 * <p>Because it is pure data, new fixes are added here without touching the diagnosis pipeline. Every
 * remediation in the catalog has a {@link Remediation#revertId()} that resolves to another catalog
 * entry, so {@link #revertOf(Remediation)} always finds the undo (the reversibility guarantee).
 *
 * <p>The suggestion map only points a subsystem at the fixes that <b>reduce</b> its cost — the paired
 * "undo" entries (e.g. {@code dab-off}, {@code vanilla-redstone}) live in the catalog for {@code --revert}
 * but are never proactively surfaced by the doctor.
 */
public final class RemediationCatalog {

    private final Map<String, Remediation> byId;
    private final Map<Subsystem, List<String>> suggestionsBySubsystem;

    /**
     * @param remediations           all remediations (ids must be unique; each revertId must resolve)
     * @param suggestionsBySubsystem subsystem -&gt; ordered remediation ids to suggest when it dominates
     */
    public RemediationCatalog(List<Remediation> remediations,
                              Map<Subsystem, List<String>> suggestionsBySubsystem) {
        Map<String, Remediation> ids = new LinkedHashMap<>();
        for (Remediation r : remediations) {
            if (ids.putIfAbsent(r.id(), r) != null) {
                throw new IllegalArgumentException("duplicate remediation id: " + r.id());
            }
        }
        // Validate the reversibility invariant up front so a broken catalog fails loudly, not at apply time.
        for (Remediation r : ids.values()) {
            if (!ids.containsKey(r.revertId())) {
                throw new IllegalArgumentException(
                        "remediation " + r.id() + " has revertId " + r.revertId() + " not present in catalog");
            }
        }
        Map<Subsystem, List<String>> sugg = new EnumMap<>(Subsystem.class);
        for (Map.Entry<Subsystem, List<String>> e : suggestionsBySubsystem.entrySet()) {
            for (String id : e.getValue()) {
                if (!ids.containsKey(id)) {
                    throw new IllegalArgumentException(
                            "suggestion for " + e.getKey() + " references unknown remediation id: " + id);
                }
            }
            sugg.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.byId = Collections.unmodifiableMap(ids);
        this.suggestionsBySubsystem = Collections.unmodifiableMap(sugg);
    }

    /** Look up a remediation by id, or {@code null} if unknown. */
    public Remediation get(String id) {
        return byId.get(id);
    }

    /** All remediations in catalog (insertion) order. */
    public List<Remediation> all() {
        return new ArrayList<>(byId.values());
    }

    /** The remediation that reverts {@code r} (guaranteed present by the constructor's validation). */
    public Remediation revertOf(Remediation r) {
        return byId.get(Objects.requireNonNull(r, "r").revertId());
    }

    /**
     * The fixes worth suggesting when {@code s} is a dominant subsystem, in priority order. Empty for
     * subsystems the catalog has no config lever for (e.g. {@link Subsystem#PLUGINS}, whose fix is to
     * investigate the owning plugin).
     */
    public List<Remediation> forSubsystem(Subsystem s) {
        List<String> ids = suggestionsBySubsystem.get(s);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Remediation> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(byId.get(id));
        }
        return Collections.unmodifiableList(out);
    }

    // ---- built-in catalog ----------------------------------------------------------------------

    private static final RemediationCatalog DEFAULT = buildDefault();

    /** The built-in catalog from the spec's remediation table. */
    public static RemediationCatalog defaultCatalog() {
        return DEFAULT;
    }

    private static RemediationCatalog buildDefault() {
        List<Remediation> r = new ArrayList<>();

        // --- entities ---
        r.add(new Remediation("dab-on",
                "Enable Distance-Activated Behaviour (DAB)",
                "~20-40% entity tick",
                "distant mobs react slower",
                ConfigPatch.victus("optimizations.entities.dab", Boolean.TRUE),
                "dab-off"));
        r.add(new Remediation("dab-off",
                "Disable Distance-Activated Behaviour (DAB)",
                "restores vanilla-accurate distant AI",
                "raises entity tick cost again",
                ConfigPatch.victus("optimizations.entities.dab", Boolean.FALSE),
                "dab-on"));

        r.add(new Remediation("per-player-spawns",
                "Enable per-player mob spawns",
                "big SMP win",
                "fairer caps; farm rates shift",
                ConfigPatch.victus("optimizations.entities.per-player-mob-spawns", Boolean.TRUE),
                "per-player-spawns-off"));
        r.add(new Remediation("per-player-spawns-off",
                "Disable per-player mob spawns",
                "restores global mob caps",
                "one busy area can starve spawns elsewhere",
                ConfigPatch.victus("optimizations.entities.per-player-mob-spawns", Boolean.FALSE),
                "per-player-spawns"));

        r.add(new Remediation("async-tracker",
                "Enable async entity tracker",
                "~15% on entity-heavy servers",
                "needs per-player-spawns; some NPC plugins need compat-mode",
                ConfigPatch.victus("optimizations.entities.async-entity-tracker", Boolean.TRUE),
                "async-tracker-off"));
        r.add(new Remediation("async-tracker-off",
                "Disable async entity tracker",
                "maximises NPC-plugin compatibility",
                "returns tracking to the main thread",
                ConfigPatch.victus("optimizations.entities.async-entity-tracker", Boolean.FALSE),
                "async-tracker"));

        r.add(new Remediation("activation-range",
                "Enable activation-range tuning",
                "cuts entities ticked",
                "far mobs idle sooner",
                ConfigPatch.victus("optimizations.entities.activation-range-tuning", Boolean.TRUE),
                "activation-range-off"));
        r.add(new Remediation("activation-range-off",
                "Disable activation-range tuning",
                "restores vanilla activation ranges",
                "more distant entities tick every tick",
                ConfigPatch.victus("optimizations.entities.activation-range-tuning", Boolean.FALSE),
                "activation-range"));

        // --- redstone ---
        r.add(new Remediation("ac-redstone",
                "Switch to Alternate-Current redstone",
                "~10-30x on large dust networks",
                "AC changes update order (some contraptions behave differently)",
                ConfigPatch.victus("optimizations.redstone", "alternate-current"),
                "vanilla-redstone"));
        r.add(new Remediation("vanilla-redstone",
                "Switch to vanilla redstone",
                "restores exact vanilla update order",
                "loses the AC speed-up on large dust networks",
                ConfigPatch.victus("optimizations.redstone", "vanilla"),
                "ac-redstone"));

        // --- network ---
        r.add(new Remediation("compression-threshold",
                "Raise packet compression threshold",
                "net CPU on hubs",
                "bandwidth trade-off (more bytes on the wire)",
                ConfigPatch.victus("optimizations.network.compression-threshold", 512),
                "compression-threshold-reset"));
        r.add(new Remediation("compression-threshold-reset",
                "Reset packet compression threshold",
                "restores default bandwidth profile",
                "compresses more packets again (more CPU on hubs)",
                ConfigPatch.victus("optimizations.network.compression-threshold", 256),
                "compression-threshold"));

        // --- chunk load (view / simulation distance) ---
        r.add(new Remediation("view-distance",
                "Reduce view-distance",
                "broad MSPT relief",
                "smaller visible world",
                ConfigPatch.serverProperty("view-distance", 6),
                "view-distance-reset"));
        r.add(new Remediation("view-distance-reset",
                "Restore default view-distance",
                "restores visible range",
                "loads/sends more chunks again",
                ConfigPatch.serverProperty("view-distance", 10),
                "view-distance"));

        r.add(new Remediation("sim-distance",
                "Reduce simulation-distance",
                "broad MSPT relief",
                "smaller active (ticking) world",
                ConfigPatch.serverProperty("simulation-distance", 6),
                "sim-distance-reset"));
        r.add(new Remediation("sim-distance-reset",
                "Restore default simulation-distance",
                "restores active range",
                "ticks more chunks again",
                ConfigPatch.serverProperty("simulation-distance", 10),
                "sim-distance"));

        Map<Subsystem, List<String>> suggestions = new EnumMap<>(Subsystem.class);
        suggestions.put(Subsystem.ENTITIES,
                List.of("dab-on", "per-player-spawns", "async-tracker", "activation-range"));
        suggestions.put(Subsystem.BLOCK_ENTITIES,
                List.of("sim-distance"));
        suggestions.put(Subsystem.REDSTONE,
                List.of("ac-redstone"));
        suggestions.put(Subsystem.CHUNK_GEN,
                List.of("view-distance", "sim-distance"));
        suggestions.put(Subsystem.CHUNK_IO,
                List.of("view-distance", "sim-distance"));
        suggestions.put(Subsystem.NETWORK,
                List.of("compression-threshold"));
        // PLUGINS + OTHER have no config lever: the doctor points at the offender instead.

        return new RemediationCatalog(r, suggestions);
    }
}

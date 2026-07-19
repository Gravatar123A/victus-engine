// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

/**
 * The stable, versioned contract of metric names, label keys, and subsystem tokens Victus exposes —
 * lifted verbatim from {@code docs/phase-2/01-metrics-observability.md}. Modules 02 (lag-doctor),
 * 03 (plugin attribution) and 04 (per-instance limits) reference these constants rather than
 * hand-typing strings, so a rename is a compile error, not a silent dashboard break.
 *
 * <p>{@link #registerDefaults(MetricRegistry)} seeds a registry with every metric's type and
 * {@code # HELP} text up front, giving well-formed exposition the moment the first sample lands.
 */
public final class MetricCatalog {

    private MetricCatalog() {
    }

    // ---- metric names (victus_ prefix) ----

    /** Ticks per second (gauge). */
    public static final String TPS = "victus_tps";
    /** Milliseconds per tick distribution (summary; {@code quantile}). */
    public static final String MSPT = "victus_mspt";
    /** Per-subsystem MSPT distribution (summary; {@code subsystem}, {@code quantile}). */
    public static final String MSPT_BY_SUBSYSTEM = "victus_mspt_by_subsystem";
    /** Loaded entities (gauge; {@code world}, {@code type}). */
    public static final String ENTITIES = "victus_entities";
    /** Loaded block entities / tile entities (gauge; {@code world}, {@code type}). */
    public static final String BLOCK_ENTITIES = "victus_block_entities";
    /** Loaded chunks (gauge; {@code world}). */
    public static final String CHUNKS_LOADED = "victus_chunks_loaded";
    /** Online players (gauge). */
    public static final String PLAYERS = "victus_players";
    /** Used JVM heap in bytes (gauge). */
    public static final String HEAP_BYTES = "victus_heap_bytes";
    /** Maximum JVM heap in bytes (gauge). */
    public static final String HEAP_MAX_BYTES = "victus_heap_max_bytes";
    /** GC pause duration distribution in ms (summary; {@code collector}, {@code quantile}). */
    public static final String GC_PAUSE_MS = "victus_gc_pause_ms";
    /** Whether a subsystem throttle is active, 0/1 (gauge; {@code subsystem}) — from module 04. */
    public static final String THROTTLE_ACTIVE = "victus_throttle_active";
    /** Per-plugin time distribution in ms (summary; {@code plugin}, {@code phase}) — from module 03. */
    public static final String PLUGIN_TIME_MS = "victus_plugin_time_ms";
    /** Network-loop CPU utilisation ratio 0..1 (gauge; {@code loop}) — from spec 04-network. */
    public static final String NETLOOP_CPU_RATIO = "victus_netloop_cpu_ratio";
    /** cgroup CPU quota (gauge) — from module 04. */
    public static final String CGROUP_CPU_QUOTA = "victus_cgroup_cpu_quota";
    /** cgroup memory limit in bytes (gauge) — from module 04. */
    public static final String CGROUP_MEM_LIMIT_BYTES = "victus_cgroup_mem_limit_bytes";

    // ---- label keys ----

    public static final String LABEL_WORLD = "world";
    public static final String LABEL_TYPE = "type";
    public static final String LABEL_SUBSYSTEM = "subsystem";
    public static final String LABEL_QUANTILE = "quantile";
    public static final String LABEL_COLLECTOR = "collector";
    public static final String LABEL_PLUGIN = "plugin";
    public static final String LABEL_PHASE = "phase";
    public static final String LABEL_LOOP = "loop";

    // ---- subsystem tokens (values for the {subsystem} label / tick-timing phases) ----

    public static final String SUBSYSTEM_ENTITIES = "entities";
    public static final String SUBSYSTEM_BLOCK_ENTITIES = "block-entities";
    public static final String SUBSYSTEM_REDSTONE = "redstone";
    public static final String SUBSYSTEM_CHUNK_GEN = "chunk-gen";
    public static final String SUBSYSTEM_CHUNK_IO = "chunk-io";
    public static final String SUBSYSTEM_NETWORK = "network";
    public static final String SUBSYSTEM_PLUGINS = "plugins";
    public static final String SUBSYSTEM_OTHER = "other";

    /** The canonical tick-timing phases, in tick order. */
    public static final String[] SUBSYSTEMS = {
            SUBSYSTEM_ENTITIES, SUBSYSTEM_BLOCK_ENTITIES, SUBSYSTEM_REDSTONE,
            SUBSYSTEM_CHUNK_GEN, SUBSYSTEM_CHUNK_IO, SUBSYSTEM_NETWORK,
            SUBSYSTEM_PLUGINS, SUBSYSTEM_OTHER
    };

    /**
     * Register the type + {@code # HELP} for every catalog metric on {@code registry}, so exposition
     * is well-formed from the first scrape. Idempotent.
     */
    public static void registerDefaults(MetricRegistry registry) {
        registry.describe(TPS, MetricType.GAUGE, "Server ticks per second.");
        registry.describe(MSPT, MetricType.SUMMARY, "Milliseconds per tick.");
        registry.describe(MSPT_BY_SUBSYSTEM, MetricType.SUMMARY, "Milliseconds per tick attributed to each tick subsystem.");
        registry.describe(ENTITIES, MetricType.GAUGE, "Loaded entities by world and type.");
        registry.describe(BLOCK_ENTITIES, MetricType.GAUGE, "Loaded block entities by world and type.");
        registry.describe(CHUNKS_LOADED, MetricType.GAUGE, "Loaded chunks by world.");
        registry.describe(PLAYERS, MetricType.GAUGE, "Online players.");
        registry.describe(HEAP_BYTES, MetricType.GAUGE, "Used JVM heap in bytes.");
        registry.describe(HEAP_MAX_BYTES, MetricType.GAUGE, "Maximum JVM heap in bytes.");
        registry.describe(GC_PAUSE_MS, MetricType.SUMMARY, "Garbage-collector pause duration in milliseconds by collector.");
        registry.describe(THROTTLE_ACTIVE, MetricType.GAUGE, "Whether a per-instance throttle is active (1) or not (0), by subsystem.");
        registry.describe(PLUGIN_TIME_MS, MetricType.SUMMARY, "Per-plugin execution time in milliseconds by plugin and phase.");
        registry.describe(NETLOOP_CPU_RATIO, MetricType.GAUGE, "Network event-loop CPU utilisation ratio (0..1) by loop.");
        registry.describe(CGROUP_CPU_QUOTA, MetricType.GAUGE, "cgroup CPU quota granted to this instance.");
        registry.describe(CGROUP_MEM_LIMIT_BYTES, MetricType.GAUGE, "cgroup memory limit in bytes for this instance.");
    }
}

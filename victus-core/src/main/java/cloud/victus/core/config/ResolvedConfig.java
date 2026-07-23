// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.config;

import java.util.ArrayList;
import java.util.List;

/** Fully-resolved, typed engine settings after precedence + profile overlay + validation. */
public final class ResolvedConfig {
    public Profile profile = Profile.SMP;
    public ThreadingMode threadingMode = ThreadingMode.SINGLE;
    public RedstoneImpl redstone = RedstoneImpl.VANILLA;
    public CompressionBackend compression = CompressionBackend.LIBDEFLATE;
    public int compressionThreshold = 256;
    public boolean dab = true;
    /** DAB: distance (blocks) within which mobs always full-tick AI. */
    public int dabStartDistance = 12;
    /** Derived {@code dabStartDistance^2}, precomputed so the hot path avoids a multiply. */
    public int dabStartDistanceSq = 144;
    /** DAB: hard cap on the AI tick interval (a far mob ticks AI at most once every N ticks). */
    public int dabMaxTickInterval = 20;
    /** DAB: right-shift exponent mapping squared distance to interval ({@code interval = d2 >> mod}). */
    public int dabActivationDistMod = 8;
    /** DAB: entity type ids (e.g. {@code minecraft:villager}) that always full-tick; empty = none. */
    public final List<String> dabBlacklist = new ArrayList<>();
    /**
     * Async pathfinding master switch. Default {@code false}: it is opt-in until a gameplay soak
     * signs it off (see docs/phase-3/01-async-pathfinding-design.md §7) — a subtle threading bug
     * would affect all mob AI, so we ship it disabled and let operators enable + soak it.
     */
    public boolean asyncPathfinding = false;
    /** Worker threads for async pathfinding; 0 = auto ({@code max(cores/3, 1)}). */
    public int asyncPathfindingMaxThreads = 0;
    /** Bounded work-queue size; 0 = auto ({@code maxThreads * 256}). */
    public int asyncPathfindingQueueSize = 0;
    /** Idle worker keep-alive (seconds). */
    public int asyncPathfindingKeepaliveSeconds = 60;
    /** Saturation policy: CALLER_RUNS (compute on the main thread = degrade to sync) or FLUSH_ALL. */
    public String asyncPathfindingRejectPolicy = "CALLER_RUNS";
    /** Offload ground navigation (the common/expensive case). */
    public boolean asyncPathfindingGround = true;
    /** Offload flying navigation (v1.1 — off until audited). */
    public boolean asyncPathfindingFlying = false;
    /** Offload water/amphibious navigation (v2 — the known async regression; off). */
    public boolean asyncPathfindingWater = false;

    /**
     * Async chunk send: serialize chunk packets on a worker (snapshot-on-main → serialize-off-thread →
     * send via Paper's existing isReady() FIFO). Default {@code false} — opt-in pending a gameplay soak
     * (see docs/phase-3/02-async-chunk-send-design.md); ordering is Paper's proven rail but it still
     * touches the network hot path, so it ships disabled.
     */
    public boolean asyncChunkSend = false;
    /** Serializer worker threads; -1 = auto ({@code clamp(cores/4, 1, 4)}). */
    public int asyncChunkSendThreads = -1;
    /** Bounded work-queue capacity; -1 = auto; overflow runs inline on main (CallerRuns = safe sync fallback). */
    public int asyncChunkSendQueueCapacity = -1;
    /** A not-ready shell past this many ms is force-rebuilt on the main thread (anti-stall watchdog). */
    public long asyncChunkSendWatchdogMs = 1500L;
    /** When true, chunks needing Anti-Xray always take the vanilla synchronous path (max-conservative). */
    public boolean asyncChunkSendAntiXrayForceSync = false;
    /** On a worker exception, rebuild the packet synchronously on main (true) vs rethrow (debug only). */
    public boolean asyncChunkSendFallbackOnException = true;

    public boolean perPlayerMobSpawns = true;
    public int maxMspt = 45;
    /** Per-world monster spawn cap; -1 = use the server/vanilla default. */
    public int monsterSpawnCap = -1;
    /** Per-chunk save cap for pile-prone projectiles/orbs (arrows, XP, etc.); -1 = off (vanilla). */
    public int projectileSaveLimit = -1;

    /**
     * Hosting: true only on a DEDICATED (not shared/oversold) node. Unlocks core-hungry tuning that would
     * steal cores from co-located servers on a shared box — chiefly uncapping Moonrise chunk worker-threads
     * (Moonrise deliberately caps to ~cores/4 for shared-hosting fairness). Default {@code false} = safe.
     */
    public boolean dedicated = false;
    /** Hosting: physical cores available to this instance; -1 = auto-detect at runtime. */
    public int nodeCores = -1;
    /** Hosting: node storage is NVMe/SSD → worth extra chunk I/O threads (dedicated boxes only). */
    public boolean nodeNvme = false;

    /**
     * Hybrid mod bridge — run Fabric/NeoForge mods alongside Bukkit/Spigot/Paper plugins on one server.
     * Isolated module, OFF by default: the loader RUNTIME is still in development (Phase 4 — see
     * docs/phase-4/04). When enabled, mods are discovered from {@code mods/}; full execution lands later.
     */
    public boolean hybridEnabled = false;
    /** Mod loader to bootstrap: {@code auto} | {@code fabric} | {@code neoforge}. */
    public String hybridLoader = "auto";
    /** Auto-disable known-bad mod↔plugin interactions when the bridge runs. */
    public boolean hybridSafeMode = true;

    /** Non-fatal advisories surfaced at boot and in {@code /victus doctor}. */
    public final List<String> warnings = new ArrayList<>();

    @Override
    public String toString() {
        return "ResolvedConfig{profile=" + profile
                + ", threadingMode=" + threadingMode
                + ", redstone=" + redstone
                + ", compression=" + compression
                + ", compressionThreshold=" + compressionThreshold
                + ", dab=" + dab
                + ", dabStartDistance=" + dabStartDistance
                + ", dabMaxTickInterval=" + dabMaxTickInterval
                + ", dabActivationDistMod=" + dabActivationDistMod
                + ", dabBlacklist=" + dabBlacklist
                + ", asyncPathfinding=" + asyncPathfinding
                + ", asyncPathfindingMaxThreads=" + asyncPathfindingMaxThreads
                + ", asyncPathfindingQueueSize=" + asyncPathfindingQueueSize
                + ", asyncPathfindingGround=" + asyncPathfindingGround
                + ", asyncChunkSend=" + asyncChunkSend
                + ", asyncChunkSendThreads=" + asyncChunkSendThreads
                + ", perPlayerMobSpawns=" + perPlayerMobSpawns
                + ", maxMspt=" + maxMspt
                + ", monsterSpawnCap=" + monsterSpawnCap
                + ", projectileSaveLimit=" + projectileSaveLimit
                + ", dedicated=" + dedicated
                + ", nodeNvme=" + nodeNvme
                + ", warnings=" + warnings + "}";
    }
}

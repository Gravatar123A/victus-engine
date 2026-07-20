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
    public boolean asyncPathfinding = true;
    public boolean perPlayerMobSpawns = true;
    public int maxMspt = 45;
    /** Per-world monster spawn cap; -1 = use the server/vanilla default. */
    public int monsterSpawnCap = -1;
    /** Per-chunk save cap for pile-prone projectiles/orbs (arrows, XP, etc.); -1 = off (vanilla). */
    public int projectileSaveLimit = -1;

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
                + ", perPlayerMobSpawns=" + perPlayerMobSpawns
                + ", maxMspt=" + maxMspt
                + ", monsterSpawnCap=" + monsterSpawnCap
                + ", projectileSaveLimit=" + projectileSaveLimit
                + ", warnings=" + warnings + "}";
    }
}

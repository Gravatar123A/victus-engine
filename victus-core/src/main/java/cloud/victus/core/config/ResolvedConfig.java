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
    public boolean asyncPathfinding = true;
    public boolean perPlayerMobSpawns = true;
    public int maxMspt = 45;
    /** Per-world monster spawn cap; -1 = use the server/vanilla default. */
    public int monsterSpawnCap = -1;

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
                + ", asyncPathfinding=" + asyncPathfinding
                + ", perPlayerMobSpawns=" + perPlayerMobSpawns
                + ", maxMspt=" + maxMspt
                + ", monsterSpawnCap=" + monsterSpawnCap
                + ", warnings=" + warnings + "}";
    }
}

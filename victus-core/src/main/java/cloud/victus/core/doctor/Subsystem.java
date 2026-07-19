// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

/**
 * A tick-time cost centre the lag-doctor can attribute MSPT to and rank. These mirror the per-cause
 * breakdown the fork reconstructs from module&nbsp;01's timing (see {@code docs/phase-2/02-lag-doctor.md}),
 * i.e. the categories Paper Timings&nbsp;v2 used to expose.
 *
 * <p>Each constant carries a stable, lowercase, hyphenated {@link #key()} used in the JSON report and
 * anywhere the subsystem is named on the wire, plus a human {@link #label()} for the text report. The
 * enum order is <b>not</b> significant — subsystems are always ranked by measured tick share.
 */
public enum Subsystem {

    /** Living/non-living entity ticking (AI, movement, tracking). Usually the #1 offender on SMPs. */
    ENTITIES("entities", "Entities"),
    /** Block-entity / tile-entity ticking (hopper chains, furnaces, spawners). */
    BLOCK_ENTITIES("block-entities", "Block entities"),
    /** Redstone dust/network propagation. */
    REDSTONE("redstone", "Redstone"),
    /** World/terrain generation of newly-explored chunks. */
    CHUNK_GEN("chunk-gen", "Chunk generation"),
    /** Chunk load/save to disk (region I/O). */
    CHUNK_IO("chunk-io", "Chunk I/O"),
    /** Packet encode/compression and network flush on the tick thread. */
    NETWORK("network", "Network"),
    /** Plugin event handlers and synchronous scheduler tasks (attributed via module&nbsp;03). */
    PLUGINS("plugins", "Plugins"),
    /** Everything not otherwise categorised (weather, fluids, misc engine work). */
    OTHER("other", "Other");

    private final String key;
    private final String label;

    Subsystem(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Stable machine key used in the JSON report (lowercase, hyphenated), e.g. {@code block-entities}. */
    public String key() {
        return key;
    }

    /** Human-readable label for the text report, e.g. {@code Block entities}. */
    public String label() {
        return label;
    }

    /**
     * Resolve a {@link Subsystem} from its {@link #key()} (case- and separator-insensitive: {@code _}
     * and {@code -} are equivalent), falling back to {@link #OTHER} for unknown names so an unexpected
     * upstream category never breaks a diagnosis.
     *
     * @param s a subsystem key such as {@code entities} or {@code chunk_gen}; {@code null} yields {@link #OTHER}
     * @return the matching subsystem, or {@link #OTHER} if none matches
     */
    public static Subsystem fromKey(String s) {
        if (s == null) {
            return OTHER;
        }
        String norm = s.trim().toLowerCase().replace('_', '-');
        for (Subsystem sub : values()) {
            if (sub.key.equals(norm)) {
                return sub;
            }
        }
        return OTHER;
    }
}

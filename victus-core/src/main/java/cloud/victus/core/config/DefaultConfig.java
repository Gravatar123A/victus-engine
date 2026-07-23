// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.config;

/**
 * The single canonical {@code victus.yml} template, written verbatim when no config file exists.
 *
 * <p>ONE owner (review #3): both the native engine bootstrap ({@code cloud.victus.engine.VictusEngine})
 * and the {@code VictusPlugin} write this exact text, so the two no longer drift. Kept in dependency-free
 * victus-core so {@link ConfigSelfTest} can assert invariants on it directly (e.g. that it never
 * force-enables an un-soaked, opt-in feature — review #6).
 */
public final class DefaultConfig {

    private DefaultConfig() {
    }

    /** The default victus.yml contents. Opt-in / un-soaked features are left COMMENTED here. */
    public static final String YML = ""
            + "# Victus Engine config. See docs/VICTUS-CONFIG.md for the full schema.\n"
            + "engine:\n"
            + "  profile: smp            # smp | technical | minigames | modded | network\n"
            + "threading:\n"
            + "  mode: single            # single | parallel | regionized\n"
            + "optimizations:\n"
            + "  # Left commented so the chosen 'profile' decides. Uncomment to force a value regardless\n"
            + "  # of profile (e.g. technical wants vanilla redstone + dab off).\n"
            + "  #redstone: alternate-current   # vanilla | alternate-current | eigencraft\n"
            + "  entities:\n"
            + "    #dab: true                   # distance-throttle far mob AI (profile decides if unset)\n"
            + "    #dab-start-distance: 12      # blocks within which mobs always full-tick AI\n"
            + "    #dab-max-tick-interval: 20   # a far mob ticks AI at most once every N ticks\n"
            + "    #async-pathfinding: true     # OFF by default (un-soaked); uncomment to opt in\n"
            + "    per-player-mob-spawns: true\n"
            + "  network:\n"
            + "    compression: libdeflate     # zlib | libdeflate (NOT zstd — breaks clients)\n"
            + "    compression-threshold: 256\n"
            + "hosting:\n"
            + "  #dedicated: false        # true ONLY on a dedicated node → uncaps chunk worker-threads (never on shared/oversold)\n"
            + "  #node-nvme: false        # true if the node uses NVMe/SSD → extra chunk I/O threads (dedicated only)\n"
            + "  limits:\n"
            + "    max-mspt: 45\n"
            + "  metrics:\n"
            + "    prometheus:\n"
            + "      enabled: true\n"
            + "      bind: 127.0.0.1\n"
            + "      port: 9940\n"
            + "  logging:\n"
            + "    format: text            # text | json\n"
            + "  lag-doctor:\n"
            + "    enabled: true\n";
}

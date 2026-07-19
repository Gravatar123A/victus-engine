// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.util.Objects;

/**
 * A drilled-down, located contributor to a hot {@link Subsystem} — the "where + who" the doctor attaches
 * to a ranked subsystem (see {@code docs/phase-2/02-lag-doctor.md} step&nbsp;3, "drill the top offenders").
 *
 * <p>This is injected data: the pure core does not read live entity/chunk indexes, the fork supplies
 * these. Example: {@code key="minecraft:zombie"}, {@code value="9412 in chunk 47,-12 (world_nether)"},
 * {@code owningPlugin="FarmPlugin"}.
 *
 * @param key          what the offender is, e.g. an entity type, tile-entity cluster, or plugin name
 * @param value        the located detail/quantity, e.g. {@code 9412 in chunk 47,-12 (world_nether)}
 * @param owningPlugin the plugin that spawned/holds it, or {@code null} if not attributable to a plugin
 */
public record Offender(String key, String value, String owningPlugin) {

    public Offender {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        // owningPlugin is intentionally nullable: not every offender is a plugin's fault.
    }

    /** An offender with no plugin attribution. */
    public static Offender of(String key, String value) {
        return new Offender(key, value, null);
    }

    /** {@code true} when this offender is attributed to a named plugin. */
    public boolean hasOwner() {
        return owningPlugin != null && !owningPlugin.isBlank();
    }
}

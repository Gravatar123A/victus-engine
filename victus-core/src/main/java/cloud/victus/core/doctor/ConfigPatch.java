// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.util.Objects;

/**
 * A single, concrete config write a {@link Remediation} performs: the target file, the dotted key, and
 * the value to set. Kept as data (no I/O here) so the pure core can describe and render a fix without
 * touching the filesystem — the fork applies it.
 *
 * <p>Most fixes target {@value #VICTUS_YML}; a couple ({@code view-distance}/{@code sim-distance}) target
 * {@value #SERVER_PROPERTIES}. The {@code value} is a plain JDK type ({@link Boolean}, {@link String},
 * {@link Number}) that a YAML/properties writer can serialise directly.
 *
 * @param file  the config file to edit, one of {@link #VICTUS_YML} or {@link #SERVER_PROPERTIES}
 * @param key   the dotted key within {@code file}, e.g. {@code optimizations.entities.dab}
 * @param value the value to write, e.g. {@link Boolean#TRUE} or {@code "alternate-current"}
 */
public record ConfigPatch(String file, String key, Object value) {

    /** The engine's own config file. */
    public static final String VICTUS_YML = "victus.yml";
    /** Vanilla server config file (view-distance / simulation-distance live here). */
    public static final String SERVER_PROPERTIES = "server.properties";

    public ConfigPatch {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(key, "key");
        // value may legitimately be any non-null JDK scalar; null is disallowed so a patch is always complete.
        Objects.requireNonNull(value, "value");
    }

    /** Convenience for a {@value #VICTUS_YML} write. */
    public static ConfigPatch victus(String key, Object value) {
        return new ConfigPatch(VICTUS_YML, key, value);
    }

    /** Convenience for a {@value #SERVER_PROPERTIES} write. */
    public static ConfigPatch serverProperty(String key, Object value) {
        return new ConfigPatch(SERVER_PROPERTIES, key, value);
    }

    /** Render as {@code file key = value}, e.g. {@code victus.yml optimizations.entities.dab = true}. */
    @Override
    public String toString() {
        return file + " " + key + " = " + value;
    }
}

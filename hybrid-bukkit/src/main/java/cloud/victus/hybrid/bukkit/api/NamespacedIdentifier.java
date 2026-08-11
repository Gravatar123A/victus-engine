// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.api;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier for vanilla, Bukkit, and modded content without enum mutation. */
public record NamespacedIdentifier(String namespace, String path) implements Comparable<NamespacedIdentifier> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9._/\\-]+");

    public NamespacedIdentifier {
        namespace = normalize(namespace, "namespace");
        path = normalize(path, "path");
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid namespaced identifier: " + namespace + ":" + path);
        }
    }

    public static NamespacedIdentifier parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("Expected namespace:path, got '" + value + "'");
        }
        return new NamespacedIdentifier(value.substring(0, separator), value.substring(separator + 1));
    }

    private static String normalize(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return normalized;
    }

    @Override
    public int compareTo(NamespacedIdentifier other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}

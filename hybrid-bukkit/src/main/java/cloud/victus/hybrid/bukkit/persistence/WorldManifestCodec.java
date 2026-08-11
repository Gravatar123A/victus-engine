// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.persistence;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Deterministic JDK-properties codec for the world hybrid manifest. */
public final class WorldManifestCodec {
    public void write(WorldManifest manifest, Writer output) throws IOException {
        output.write("format=" + manifest.formatVersion() + "\n");
        output.write("dimension=" + manifest.dimension() + "\n");
        output.write("fingerprint=" + manifest.compatibilityFingerprint() + "\n");
        output.write("required=" + String.join(",", manifest.requiredContent().stream().map(Object::toString).toList()) + "\n");
    }

    public WorldManifest read(Reader input) throws IOException {
        Properties properties = new Properties();
        properties.load(input);
        String required = require(properties, "required");
        List<NamespacedIdentifier> identifiers = required.isBlank() ? List.of() : Arrays.stream(required.split(",", -1))
                .map(NamespacedIdentifier::parse).toList();
        try {
            return new WorldManifest(
                    Integer.parseInt(require(properties, "format")),
                    NamespacedIdentifier.parse(require(properties, "dimension")),
                    require(properties, "fingerprint"), identifiers);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid hybrid world manifest", failure);
        }
    }

    private static String require(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) throw new IOException("Missing hybrid world manifest key " + key);
        return value.trim();
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Canonical compatibility identity for reports and curated support policy. */
public record CompatibilityFingerprint(
        String minecraftVersion,
        String paperCommit,
        LoaderProfile profile,
        String loaderVersion,
        String apiVersion,
        String javaVersion,
        List<String> modIds
) {
    public CompatibilityFingerprint {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(paperCommit, "paperCommit");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(loaderVersion, "loaderVersion");
        Objects.requireNonNull(apiVersion, "apiVersion");
        Objects.requireNonNull(javaVersion, "javaVersion");
        List<String> sorted = new ArrayList<>(modIds == null ? List.of() : modIds);
        sorted.replaceAll(String::trim);
        sorted.removeIf(String::isEmpty);
        sorted.sort(String::compareTo);
        modIds = List.copyOf(sorted);
    }

    public String canonical() {
        return String.join("|",
                "mc=" + minecraftVersion,
                "paper=" + paperCommit,
                "profile=" + profile.name().toLowerCase(),
                "loader=" + loaderVersion,
                "api=" + apiVersion,
                "java=" + javaVersion,
                "mods=" + String.join(",", modIds));
    }

    public String sha256() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}

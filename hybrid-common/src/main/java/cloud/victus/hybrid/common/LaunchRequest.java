// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Fully parsed launch request; adapters receive paths and target main explicitly. */
public record LaunchRequest(
        LoaderProfile profile,
        String targetMain,
        Path targetArtifact,
        List<Path> targetClasspath,
        List<Path> adapterClasspath,
        List<Path> loaderClasspath,
        Path gameDirectory,
        Path modsDirectory,
        List<String> targetArguments,
        Path startupReport
) {
    public LaunchRequest {
        Objects.requireNonNull(profile, "profile");
        if (targetMain == null || targetMain.isBlank()) {
            throw new IllegalArgumentException("targetMain is required");
        }
        targetClasspath = List.copyOf(targetClasspath == null ? List.of() : targetClasspath);
        adapterClasspath = List.copyOf(adapterClasspath == null ? List.of() : adapterClasspath);
        loaderClasspath = List.copyOf(loaderClasspath == null ? List.of() : loaderClasspath);
        gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
        modsDirectory = Objects.requireNonNull(modsDirectory, "modsDirectory").toAbsolutePath().normalize();
        targetArguments = List.copyOf(targetArguments == null ? List.of() : targetArguments);
        Objects.requireNonNull(startupReport, "startupReport");
        if (profile != LoaderProfile.DISABLED && targetArtifact == null) {
            throw new IllegalArgumentException("targetArtifact is required for loader profiles");
        }
    }
}

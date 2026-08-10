// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Dependency-free line report written atomically so startup failures remain diagnosable. */
public final class StartupReport {
    private final LoaderProfile profile;
    private final List<String> diagnostics = new ArrayList<>();
    private String adapter = "none";
    private String blocker = "none";
    private CompatibilityFingerprint fingerprint;

    public StartupReport(LoaderProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public synchronized void adapter(String value) {
        adapter = sanitize(value);
    }

    public synchronized void diagnostic(String value) {
        diagnostics.add(sanitize(value));
    }

    public synchronized void blocker(String value) {
        blocker = sanitize(value);
    }

    public synchronized void fingerprint(CompatibilityFingerprint value) {
        fingerprint = value;
    }

    public synchronized String render(LifecycleTracker lifecycle) {
        StringBuilder out = new StringBuilder("format=victus-hybrid-report-v1\n");
        out.append("profile=").append(profile.name().toLowerCase()).append('\n');
        out.append("adapter=").append(adapter).append('\n');
        out.append("state=").append(lifecycle.state().name()).append('\n');
        out.append("history=").append(String.join(",", lifecycle.history().stream().map(Enum::name).toList())).append('\n');
        out.append("blocker=").append(blocker).append('\n');
        if (fingerprint != null) {
            out.append("fingerprint.canonical=").append(fingerprint.canonical()).append('\n');
            out.append("fingerprint.sha256=").append(fingerprint.sha256()).append('\n');
        }
        for (int i = 0; i < diagnostics.size(); i++) {
            out.append("diagnostic.").append(i + 1).append('=').append(diagnostics.get(i)).append('\n');
        }
        return out.toString();
    }

    public synchronized void write(Path destination, LifecycleTracker lifecycle) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, render(lifecycle), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sanitize(String value) {
        return String.valueOf(value).replace('\r', ' ').replace('\n', ' ');
    }
}

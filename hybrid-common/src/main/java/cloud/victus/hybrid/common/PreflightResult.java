// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.util.List;

/** Deterministic preflight outcome; warnings do not permit bypassing errors. */
public record PreflightResult(boolean ready, List<String> diagnostics, CompatibilityFingerprint fingerprint) {
    public PreflightResult {
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public static PreflightResult failure(String diagnostic) {
        return new PreflightResult(false, List.of(diagnostic), null);
    }
}

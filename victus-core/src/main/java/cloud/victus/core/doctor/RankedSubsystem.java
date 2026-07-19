// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.util.List;
import java.util.Objects;

/**
 * A single ranked entry in a {@link DiagnosisReport}: a subsystem, its measured cost and tick share,
 * whether it is a <b>dominant</b> offender (i.e. worth remediating), the drilled-down {@link Offender}s
 * located within it, and the {@link Remediation}s suggested for it.
 *
 * @param subsystem    the cost centre
 * @param ms           milliseconds-per-tick attributed to it over the window
 * @param pct          share of the measured tick, in percent
 * @param dominant     whether the diagnosis flagged it as a dominant offender
 * @param offenders    located contributors (may be empty; truncated to the report's max entries)
 * @param remediations suggested fixes (empty unless {@code dominant} and the catalog has a lever)
 */
public record RankedSubsystem(
        Subsystem subsystem,
        double ms,
        double pct,
        boolean dominant,
        List<Offender> offenders,
        List<Remediation> remediations) {

    public RankedSubsystem {
        Objects.requireNonNull(subsystem, "subsystem");
        offenders = List.copyOf(offenders);
        remediations = List.copyOf(remediations);
    }
}

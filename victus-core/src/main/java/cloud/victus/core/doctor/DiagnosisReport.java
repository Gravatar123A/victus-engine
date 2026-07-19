// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The immutable result of a lag-doctor run: the window, headline TPS/MSPT, the subsystems ranked by tick
 * share (each with located offenders and per-subsystem fixes), and the aggregated, de-duplicated list of
 * suggested {@link Remediation}s across all dominant subsystems.
 *
 * <p>Render it with {@link DoctorRenderer} to a human report or the JSON shape described in
 * {@code docs/phase-2/02-lag-doctor.md}.
 *
 * @param window       the diagnosis window
 * @param tps          measured ticks-per-second over the window
 * @param mspt         MSPT distribution summary
 * @param subsystems   subsystems ranked most-expensive-first
 * @param remediations aggregated suggested fixes (rank order, de-duplicated by id)
 */
public record DiagnosisReport(
        Duration window,
        double tps,
        MsptSummary mspt,
        List<RankedSubsystem> subsystems,
        List<Remediation> remediations) {

    public DiagnosisReport {
        Objects.requireNonNull(window, "window");
        mspt = mspt == null ? MsptSummary.EMPTY : mspt;
        subsystems = List.copyOf(subsystems);
        remediations = List.copyOf(remediations);
    }

    /** The top-ranked (most expensive) subsystem, or empty if the breakdown had no slices. */
    public RankedSubsystem topOffender() {
        return subsystems.isEmpty() ? null : subsystems.get(0);
    }
}

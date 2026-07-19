// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a {@link DiagnosisReport} from an injected {@link TickBreakdown}, MSPT/TPS numbers and per-
 * subsystem {@link Offender} lists — the diagnosis pipeline from {@code docs/phase-2/02-lag-doctor.md}:
 * <ol>
 *   <li><b>rank</b> subsystems by tick share;</li>
 *   <li><b>flag</b> the dominant offenders (share &ge; {@link #dominanceThresholdPct}, plus the #1 always);</li>
 *   <li><b>drill</b> each with its supplied offenders;</li>
 *   <li><b>attach</b> the catalog's remediations for every dominant subsystem, aggregating them
 *       (rank order, de-duplicated) into the report's top-level list.</li>
 * </ol>
 *
 * <p>Fluent and single-use; the builder holds no live state beyond the injected inputs. Everything it
 * needs is data, so a diagnosis is fully reproducible offline.
 */
public final class DiagnosisBuilder {

    private final RemediationCatalog catalog;
    private final Map<Subsystem, List<Offender>> offenders = new EnumMap<>(Subsystem.class);

    private Duration window = Duration.ofSeconds(60);
    private double tps = 20.0;
    private MsptSummary mspt = MsptSummary.EMPTY;
    private TickBreakdown breakdown;
    private double dominanceThresholdPct = 15.0;
    private int maxEntries = 15;

    /** @param catalog the remediation catalog to draw suggestions from (typically {@link RemediationCatalog#defaultCatalog()}) */
    public DiagnosisBuilder(RemediationCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public DiagnosisBuilder window(Duration window) {
        this.window = Objects.requireNonNull(window, "window");
        return this;
    }

    public DiagnosisBuilder tps(double tps) {
        this.tps = tps;
        return this;
    }

    public DiagnosisBuilder mspt(MsptSummary mspt) {
        this.mspt = mspt == null ? MsptSummary.EMPTY : mspt;
        return this;
    }

    public DiagnosisBuilder breakdown(TickBreakdown breakdown) {
        this.breakdown = Objects.requireNonNull(breakdown, "breakdown");
        return this;
    }

    /** Replace the offenders drilled for {@code s}. */
    public DiagnosisBuilder offenders(Subsystem s, List<Offender> list) {
        Objects.requireNonNull(s, "s");
        offenders.put(s, new ArrayList<>(list));
        return this;
    }

    /** Append one offender for {@code s}. */
    public DiagnosisBuilder addOffender(Subsystem s, Offender offender) {
        Objects.requireNonNull(s, "s");
        Objects.requireNonNull(offender, "offender");
        offenders.computeIfAbsent(s, k -> new ArrayList<>()).add(offender);
        return this;
    }

    /**
     * Tick-share (percent) at or above which a subsystem is treated as a dominant offender and gets
     * remediations attached. The single top-ranked subsystem is always treated as dominant regardless,
     * so the doctor never returns "you're lagging but here's nothing to try". Default {@code 15.0}.
     */
    public DiagnosisBuilder dominanceThresholdPct(double pct) {
        this.dominanceThresholdPct = pct;
        return this;
    }

    /** Cap on offenders per subsystem and on the aggregated remediation list ({@code hosting.lag-doctor.max-report-entries}). */
    public DiagnosisBuilder maxEntries(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
        return this;
    }

    /** Run the pipeline and produce the (immutable) report. */
    public DiagnosisReport build() {
        Objects.requireNonNull(breakdown, "breakdown must be set before build()");

        List<TickBreakdown.Slice> ranked = breakdown.ranked();
        List<RankedSubsystem> subsystems = new ArrayList<>(ranked.size());
        // Aggregate top-level remediations in rank order, de-duplicated by id.
        Set<String> seenRemediationIds = new LinkedHashSet<>();
        List<Remediation> aggregated = new ArrayList<>();

        for (int i = 0; i < ranked.size(); i++) {
            TickBreakdown.Slice slice = ranked.get(i);
            Subsystem sub = slice.subsystem();
            // #1 is always dominant so there is always something to look at; others by threshold.
            boolean dominant = (i == 0) || slice.pct() >= dominanceThresholdPct;

            List<Offender> subOffenders = offenders.getOrDefault(sub, List.of());
            if (subOffenders.size() > maxEntries) {
                subOffenders = new ArrayList<>(subOffenders.subList(0, maxEntries));
            }

            List<Remediation> subRemediations = dominant ? catalog.forSubsystem(sub) : List.of();

            subsystems.add(new RankedSubsystem(
                    sub, slice.ms(), slice.pct(), dominant, subOffenders, subRemediations));

            if (dominant) {
                for (Remediation rem : subRemediations) {
                    if (seenRemediationIds.add(rem.id()) && aggregated.size() < maxEntries) {
                        aggregated.add(rem);
                    }
                }
            }
        }

        return new DiagnosisReport(window, tps, mspt, subsystems, aggregated);
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.util.Objects;

/**
 * One reversible, one-click fix the lag-doctor can propose (and the panel/CLI can {@code --apply}).
 *
 * <p>Per {@code docs/phase-2/02-lag-doctor.md}, reversibility is <b>mandatory</b>: every remediation
 * names the id of the remediation that undoes it via {@link #revertId()}, so the compact constructor
 * requires it to be non-null. The catalog is data-driven, so adding a fix never touches the diagnosis
 * pipeline.
 *
 * @param id             stable machine id, e.g. {@code dab-on} (used by {@code --apply}/{@code --revert} and the JSON)
 * @param title          short human title, e.g. {@code Enable Distance-Activated Behaviour (DAB)}
 * @param expectedGain   illustrative, workload-dependent gain, e.g. {@code ~20-40% entity tick}
 * @param behaviorCaveat the behaviour trade-off to disclose, e.g. {@code distant mobs react slower}
 * @param writes         the concrete config write this fix performs
 * @param revertId       id of the remediation that reverts this one (never {@code null})
 */
public record Remediation(
        String id,
        String title,
        String expectedGain,
        String behaviorCaveat,
        ConfigPatch writes,
        String revertId) {

    public Remediation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(expectedGain, "expectedGain");
        Objects.requireNonNull(behaviorCaveat, "behaviorCaveat");
        Objects.requireNonNull(writes, "writes");
        // A fix without a revert is not shippable — the doctor promises reversibility.
        Objects.requireNonNull(revertId, "revertId");
        if (revertId.equals(id)) {
            throw new IllegalArgumentException("remediation " + id + " cannot revert itself");
        }
    }
}

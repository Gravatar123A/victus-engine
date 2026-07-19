// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.limits;

/**
 * The decision a {@link ThrottleController} reaches on a given tick
 * (docs/phase-2/04-per-instance-limits.md).
 */
public enum ThrottleAction {
    /** Climbed one rung: MSPT was over budget for a sustained window. */
    ESCALATE,
    /** No change this tick (under budget, recovering-but-not-yet, or inside the hysteresis band). */
    HOLD,
    /** Descended one rung: MSPT was comfortably under budget for a sustained window. */
    RELAX
}

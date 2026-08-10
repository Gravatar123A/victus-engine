// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

/** Ordered, monotonic bootstrap states shared by every loader adapter. */
public enum LifecycleState {
    CREATED,
    PREFLIGHTING,
    PREFLIGHT_PASSED,
    TRANSFORMERS_READY,
    LOADER_ENTERED,
    TARGET_DELEGATED,
    FAILED;

    public boolean canTransitionTo(LifecycleState next) {
        if (next == FAILED) {
            return this != FAILED && this != TARGET_DELEGATED;
        }
        return switch (this) {
            case CREATED -> next == PREFLIGHTING;
            case PREFLIGHTING -> next == PREFLIGHT_PASSED;
            case PREFLIGHT_PASSED -> next == TRANSFORMERS_READY || next == LOADER_ENTERED;
            case TRANSFORMERS_READY -> next == LOADER_ENTERED;
            case LOADER_ENTERED -> next == TARGET_DELEGATED;
            case TARGET_DELEGATED, FAILED -> false;
        };
    }
}

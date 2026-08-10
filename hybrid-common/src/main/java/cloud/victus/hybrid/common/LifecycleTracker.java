// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Thread-safe state recorder that rejects skipped or backwards bootstrap transitions. */
public final class LifecycleTracker {
    private LifecycleState state = LifecycleState.CREATED;
    private final List<LifecycleState> history = new ArrayList<>(List.of(LifecycleState.CREATED));

    public synchronized LifecycleState state() {
        return state;
    }

    public synchronized List<LifecycleState> history() {
        return List.copyOf(history);
    }

    public synchronized void transition(LifecycleState next) {
        Objects.requireNonNull(next, "next");
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid hybrid lifecycle transition " + state + " -> " + next);
        }
        state = next;
        history.add(next);
    }

    public synchronized void fail() {
        if (state != LifecycleState.FAILED && state != LifecycleState.TARGET_DELEGATED) {
            transition(LifecycleState.FAILED);
        }
    }
}

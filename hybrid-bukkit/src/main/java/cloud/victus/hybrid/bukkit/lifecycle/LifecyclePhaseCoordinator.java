// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict single-direction lifecycle coordinator; failures are terminal and never skipped over. */
public final class LifecyclePhaseCoordinator {
    private final List<LifecycleParticipant> participants = new ArrayList<>();
    private BridgePhase phase;

    public LifecyclePhaseCoordinator(boolean enabled) {
        phase = enabled ? BridgePhase.CREATED : BridgePhase.DISABLED;
    }

    public synchronized BridgePhase phase() {
        return phase;
    }

    public synchronized void addParticipant(LifecycleParticipant participant) {
        if (phase != BridgePhase.CREATED) {
            throw new IllegalStateException("Participants must be registered in CREATED, current=" + phase);
        }
        participants.add(Objects.requireNonNull(participant, "participant"));
    }

    public synchronized void advance(BridgePhase next) {
        Objects.requireNonNull(next, "next");
        if (!allowed(phase, next)) {
            throw new IllegalStateException("Invalid bridge transition " + phase + " -> " + next);
        }
        try {
            for (LifecycleParticipant participant : List.copyOf(participants)) participant.enter(next);
            phase = next;
        } catch (Exception failure) {
            phase = BridgePhase.FAILED;
            throw new IllegalStateException("Bridge participant failed entering " + next, failure);
        }
    }

    private static boolean allowed(BridgePhase current, BridgePhase next) {
        if (current == BridgePhase.DISABLED || current == BridgePhase.STOPPED || current == BridgePhase.FAILED) return false;
        if (next == BridgePhase.FAILED) return true;
        return switch (current) {
            case CREATED -> next == BridgePhase.LOADER_DISCOVERY;
            case LOADER_DISCOVERY -> next == BridgePhase.REGISTRATION;
            case REGISTRATION -> next == BridgePhase.REGISTRIES_FROZEN;
            case REGISTRIES_FROZEN -> next == BridgePhase.BUKKIT_BINDING;
            case BUKKIT_BINDING -> next == BridgePhase.READY;
            case READY -> next == BridgePhase.STOPPING;
            case STOPPING -> next == BridgePhase.STOPPED;
            default -> false;
        };
    }
}

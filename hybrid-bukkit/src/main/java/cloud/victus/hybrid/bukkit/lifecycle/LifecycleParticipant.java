// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.lifecycle;

/** Participant notified synchronously before a phase becomes visible. */
@FunctionalInterface
public interface LifecycleParticipant {
    void enter(BridgePhase phase) throws Exception;
}

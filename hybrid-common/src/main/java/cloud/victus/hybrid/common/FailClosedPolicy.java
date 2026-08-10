// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.util.Objects;

/** Explicit policy: enabled profiles may never bypass a failed preflight and start Paper untransformed. */
public final class FailClosedPolicy {
    public enum Decision {
        DELEGATE_DISABLED,
        ENTER_SELECTED_LOADER,
        REFUSE_STARTUP
    }

    private FailClosedPolicy() {
    }

    public static Decision decide(LoaderProfile profile, boolean preflightPassed) {
        Objects.requireNonNull(profile, "profile");
        if (profile == LoaderProfile.DISABLED) {
            return Decision.DELEGATE_DISABLED;
        }
        return preflightPassed ? Decision.ENTER_SELECTED_LOADER : Decision.REFUSE_STARTUP;
    }
}

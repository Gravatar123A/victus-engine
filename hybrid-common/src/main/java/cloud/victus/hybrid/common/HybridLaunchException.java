// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

/** Actionable fail-closed startup error carrying a stable blocker code. */
public final class HybridLaunchException extends Exception {
    private final String blockerCode;

    public HybridLaunchException(String blockerCode, String message) {
        super(message);
        this.blockerCode = blockerCode;
    }

    public HybridLaunchException(String blockerCode, String message, Throwable cause) {
        super(message, cause);
        this.blockerCode = blockerCode;
    }

    public String blockerCode() {
        return blockerCode;
    }
}

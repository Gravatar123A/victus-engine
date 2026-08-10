// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

/** Service contract implemented in loader-specific modules and loaded only after profile selection. */
public interface LoaderAdapter {
    LoaderProfile profile();

    String implementationVersion();

    PreflightResult preflight(LaunchRequest request);

    void launch(LaunchRequest request, LifecycleTracker lifecycle, StartupReport report)
            throws HybridLaunchException;
}

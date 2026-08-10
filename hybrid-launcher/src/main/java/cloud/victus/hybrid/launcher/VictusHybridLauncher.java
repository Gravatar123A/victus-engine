// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.launcher;

import cloud.victus.hybrid.common.FailClosedPolicy;
import cloud.victus.hybrid.common.HybridLaunchException;
import cloud.victus.hybrid.common.HybridMarkers;
import cloud.victus.hybrid.common.LaunchRequest;
import cloud.victus.hybrid.common.LifecycleState;
import cloud.victus.hybrid.common.LifecycleTracker;
import cloud.victus.hybrid.common.LoaderAdapter;
import cloud.victus.hybrid.common.LoaderProfile;
import cloud.victus.hybrid.common.PreflightResult;
import cloud.victus.hybrid.common.StartupReport;

import java.io.IOException;

/**
 * Dependency-light pre-main dispatcher. The disabled path cannot discover or load adapter/loader classes;
 * enabled profiles must complete adapter preflight or startup is refused rather than delegating untransformed.
 */
public final class VictusHybridLauncher {
    private VictusHybridLauncher() {
    }

    public static void main(String[] arguments) throws Exception {
        int status = run(arguments);
        if (status != 0) {
            throw new IllegalStateException("Victus hybrid startup failed with status " + status);
        }
    }

    public static int run(String[] arguments) throws IOException {
        LaunchRequest request = LauncherArguments.parse(arguments);
        LifecycleTracker lifecycle = new LifecycleTracker();
        StartupReport report = new StartupReport(request.profile());

        try {
            if (request.profile() == LoaderProfile.DISABLED) {
                if (FailClosedPolicy.decide(request.profile(), true) != FailClosedPolicy.Decision.DELEGATE_DISABLED) {
                    throw new AssertionError("disabled policy invariant");
                }
                report.diagnostic("Hybrid runtime disabled; delegating target without adapter discovery.");
                lifecycle.transition(LifecycleState.PREFLIGHTING);
                lifecycle.transition(LifecycleState.PREFLIGHT_PASSED);
                lifecycle.transition(LifecycleState.LOADER_ENTERED);
                MainDelegate.invoke(request);
                lifecycle.transition(LifecycleState.TARGET_DELEGATED);
                report.diagnostic(HybridMarkers.TARGET_DELEGATED);
                return 0;
            }

            lifecycle.transition(LifecycleState.PREFLIGHTING);
            try (IsolatedAdapterLoader isolated = IsolatedAdapterLoader.open(request.adapterClasspath())) {
                LoaderAdapter adapter = isolated.adapter(request.profile());
                report.adapter(adapter.getClass().getName() + "@" + adapter.implementationVersion());
                PreflightResult preflight = adapter.preflight(request);
                preflight.diagnostics().forEach(report::diagnostic);
                report.fingerprint(preflight.fingerprint());
                if (FailClosedPolicy.decide(request.profile(), preflight.ready())
                        != FailClosedPolicy.Decision.ENTER_SELECTED_LOADER) {
                    throw new HybridLaunchException("PROFILE_PREFLIGHT_FAILED",
                            "The " + request.profile().name().toLowerCase() + " profile failed preflight; target main was not invoked");
                }
                lifecycle.transition(LifecycleState.PREFLIGHT_PASSED);
                report.diagnostic(HybridMarkers.PREFLIGHT);
                adapter.launch(request, lifecycle, report);
                return 0;
            }
        } catch (HybridLaunchException failure) {
            lifecycle.fail();
            report.blocker(failure.blockerCode());
            report.diagnostic(failure.getMessage());
            System.err.println("Victus hybrid startup refused [" + failure.blockerCode() + "]: " + failure.getMessage());
            failure.printStackTrace(System.err);
            return 2;
        } catch (RuntimeException | Error failure) {
            lifecycle.fail();
            report.blocker("UNEXPECTED_STARTUP_FAILURE");
            report.diagnostic(failure.toString());
            throw failure;
        } finally {
            report.write(request.startupReport(), lifecycle);
        }
    }
}

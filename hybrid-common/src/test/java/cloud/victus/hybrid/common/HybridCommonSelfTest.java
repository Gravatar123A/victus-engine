// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class HybridCommonSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(LoaderProfile.parse("disabled") == LoaderProfile.DISABLED, "disabled profile");
        check(LoaderProfile.parse("FABRIC") == LoaderProfile.FABRIC, "fabric profile");
        expect(IllegalArgumentException.class, () -> LoaderProfile.parse("auto"), "auto is ambiguous and refused");

        LifecycleTracker lifecycle = new LifecycleTracker();
        lifecycle.transition(LifecycleState.PREFLIGHTING);
        lifecycle.transition(LifecycleState.PREFLIGHT_PASSED);
        lifecycle.transition(LifecycleState.TRANSFORMERS_READY);
        lifecycle.transition(LifecycleState.LOADER_ENTERED);
        lifecycle.transition(LifecycleState.TARGET_DELEGATED);
        check(lifecycle.state() == LifecycleState.TARGET_DELEGATED, "valid lifecycle");
        expect(IllegalStateException.class, () -> lifecycle.transition(LifecycleState.FAILED), "terminal state is immutable");

        LifecycleTracker skipped = new LifecycleTracker();
        expect(IllegalStateException.class, () -> skipped.transition(LifecycleState.LOADER_ENTERED), "skipped transition refused");

        check(FailClosedPolicy.decide(LoaderProfile.DISABLED, false) == FailClosedPolicy.Decision.DELEGATE_DISABLED,
                "disabled delegates regardless of loader preflight");
        check(FailClosedPolicy.decide(LoaderProfile.FABRIC, false) == FailClosedPolicy.Decision.REFUSE_STARTUP,
                "enabled profile fails closed");

        CompatibilityFingerprint first = new CompatibilityFingerprint(
                "26.2", "paper", LoaderProfile.FABRIC, "0.19.3", "0.156.0+26.2", "25", List.of("z", "a"));
        CompatibilityFingerprint second = new CompatibilityFingerprint(
                "26.2", "paper", LoaderProfile.FABRIC, "0.19.3", "0.156.0+26.2", "25", List.of("a", "z"));
        check(first.canonical().equals(second.canonical()), "fingerprint canonicalizes mod order");
        check(first.sha256().equals(second.sha256()) && first.sha256().length() == 64, "fingerprint digest deterministic");

        StartupReport report = new StartupReport(LoaderProfile.FABRIC);
        report.adapter("test");
        report.fingerprint(first);
        report.diagnostic("line one\nline two");
        Path output = Files.createTempDirectory("victus-report-test").resolve("report.txt");
        report.write(output, lifecycle);
        String text = Files.readString(output);
        check(text.contains("state=TARGET_DELEGATED"), "report state");
        check(text.contains("diagnostic.1=line one line two"), "report is line safe");
        check(text.contains("fingerprint.sha256=" + first.sha256()), "report fingerprint");

        System.out.println("HybridCommonSelfTest: " + checks + " checks passed");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingRunnable action, String name) {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(name + ": wrong failure " + failure, failure);
        }
        throw new AssertionError(name + ": no failure");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

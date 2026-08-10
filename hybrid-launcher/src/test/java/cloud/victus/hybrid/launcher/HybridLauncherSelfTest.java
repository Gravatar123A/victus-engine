// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.launcher;

import cloud.victus.hybrid.common.LaunchRequest;
import cloud.victus.hybrid.common.LoaderAdapter;
import cloud.victus.hybrid.common.LoaderProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class HybridLauncherSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("victus-launcher-test");
        Path report = temp.resolve("disabled-report.txt");
        String testClasses = codeSource();
        String[] delegated = {"--nogui", "value with spaces", "--hybrid-profile=fabric"};
        String[] launch = {
                "--hybrid-profile=disabled",
                "--hybrid-target-main=cloud.victus.hybrid.launcher.fixture.DelegationTarget",
                "--hybrid-target-classpath=" + testClasses,
                "--hybrid-report=" + report,
                "--",
                delegated[0], delegated[1], delegated[2]
        };
        LaunchRequest parsed = LauncherArguments.parse(launch);
        check(parsed.profile() == LoaderProfile.DISABLED, "profile parsing");
        check(parsed.targetArguments().equals(Arrays.asList(delegated)), "target arguments preserved unchanged");
        check(parsed.adapterClasspath().isEmpty(), "disabled has no adapter classpath");

        System.clearProperty("victus.launcher.delegated");
        check(VictusHybridLauncher.run(launch) == 0, "disabled run succeeds");
        check(String.join("", delegated).equals(System.getProperty("victus.launcher.delegated")),
                "disabled delegates exact arguments");
        check(Files.readString(report).contains("adapter=none"), "disabled report proves no adapter discovery");
        check(Files.readString(report).contains("state=TARGET_DELEGATED"), "disabled lifecycle reaches target");

        Path config = temp.resolve("launcher.properties");
        Files.writeString(config, "profile=fabric\ntargetMain=example.Main\ntargetArtifact=server.jar\nadapterClasspath=adapter.jar\nloaderClasspath=loader.jar\n");
        LaunchRequest configured = LauncherArguments.parse(new String[]{"--hybrid-config", config.toString(), "--", "server-arg"});
        check(configured.profile() == LoaderProfile.FABRIC, "config profile");
        check(configured.targetArguments().equals(java.util.List.of("server-arg")), "config target args");

        expect(IllegalArgumentException.class,
                () -> LauncherArguments.parse(new String[]{"--hybrid-profile=auto", "--hybrid-target-main=x.Main"}),
                "ambiguous auto mode refused");
        expect(IllegalArgumentException.class,
                () -> LauncherArguments.parse(new String[]{"--hybrid-target-main=x.Main", "--nogui"}),
                "target separator required");
        expect(IllegalArgumentException.class,
                () -> LauncherArguments.parse(new String[]{"--hybrid-typo=x", "--hybrid-target-main=x.Main"}),
                "unknown launcher option refused");

        // The adapter is discovered via a child ServiceLoader from its module output directory. Loader classes
        // are absent; merely selecting/discovering an adapter must not eagerly resolve Knot or FML.
        Path fabricOutput = siblingOutput("hybrid-fabric");
        if (Files.isDirectory(fabricOutput)) {
            try (IsolatedAdapterLoader isolated = IsolatedAdapterLoader.open(java.util.List.of(fabricOutput))) {
                LoaderAdapter adapter = isolated.adapter(LoaderProfile.FABRIC);
                check(adapter.profile() == LoaderProfile.FABRIC, "isolated Fabric service discovery");
                check(notLoadable("net.fabricmc.loader.impl.launch.knot.KnotServer"), "Knot absent from launcher loader");
            }
        }

        System.out.println("HybridLauncherSelfTest: " + checks + " checks passed");
    }

    private static String codeSource() throws Exception {
        return Path.of(HybridLauncherSelfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static Path siblingOutput(String project) throws Exception {
        Path test = Path.of(HybridLauncherSelfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path root = test.getParent().getParent().getParent().getParent().getParent();
        return root.resolve(project).resolve("classes/java/main");
    }

    private static boolean notLoadable(String name) {
        try {
            Class.forName(name, false, HybridLauncherSelfTest.class.getClassLoader());
            return false;
        } catch (ClassNotFoundException expected) {
            return true;
        }
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

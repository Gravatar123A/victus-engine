// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fabric;

import cloud.victus.hybrid.common.LaunchRequest;
import cloud.victus.hybrid.common.LifecycleState;
import cloud.victus.hybrid.common.LifecycleTracker;
import cloud.victus.hybrid.common.LoaderProfile;
import cloud.victus.hybrid.common.StartupReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Executable Knot/Mixin proof using an owned target; the same provider is used for the Victus server jar. */
public final class VictusFabricProviderIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path adapterJar = Path.of(args[0]).toAbsolutePath().normalize();
        Path fixtureJar = Path.of(args[1]).toAbsolutePath().normalize();
        List<Path> loaderRuntime = split(args[2]);
        Path temp = Files.createTempDirectory("victus-fabric-provider-integration");
        Path target = temp.resolve("owned-target.jar");
        createTargetJar(target);
        Path externalLibrary = temp.resolve("external-runtime-library.jar");
        createExternalLibraryJar(externalLibrary);
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Files.copy(fixtureJar, mods.resolve(fixtureJar.getFileName()));
        boolean fullFabricApi = args.length > 3;
        if (fullFabricApi) {
            Path aggregate = Path.of(args[3]).toAbsolutePath().normalize();
            Files.copy(aggregate, mods.resolve(aggregate.getFileName()));
        } else {
            for (Path runtime : loaderRuntime) {
                String name = runtime.getFileName().toString();
                if (name.startsWith("fabric-api-base-")) {
                    Files.copy(runtime, mods.resolve(runtime.getFileName()));
                }
            }
            createLifecycleApiFixture(mods.resolve("fabric-lifecycle-events-v1-test-fixture.jar"));
        }
        Path reportPath = temp.resolve("startup-report.txt");

        System.setProperty("victus.fabric.requireFixtureProof", "true");
        System.setProperty(VictusFabricGameProvider.EXTERNAL_LIBRARY_PROBE_PROPERTY,
                "cloud.victus.hybrid.fabric.fixture.ExternalRuntimeLibrary");
        clearProof();
        LaunchRequest request = new LaunchRequest(LoaderProfile.FABRIC,
                VictusFabricGameProvider.EXPECTED_TARGET_MAIN, target, List.of(externalLibrary), List.of(adapterJar),
                loaderRuntime, temp, mods, List.of("--nogui"), reportPath);
        FabricLoaderAdapter adapter = new FabricLoaderAdapter();
        if (!adapter.preflight(request).ready()) {
            throw new AssertionError("fixture preflight failed: " + adapter.preflight(request).diagnostics());
        }
        LifecycleTracker lifecycle = new LifecycleTracker();
        lifecycle.transition(LifecycleState.PREFLIGHTING);
        lifecycle.transition(LifecycleState.PREFLIGHT_PASSED);
        StartupReport report = new StartupReport(LoaderProfile.FABRIC);
        if (fullFabricApi) {
            try {
                adapter.launch(request, lifecycle, report);
                throw new AssertionError("full Fabric API unexpectedly passed against the minimal owned target");
            } catch (cloud.victus.hybrid.common.HybridLaunchException expected) {
                check("FABRIC_API_MODULE_INCOMPATIBLE".equals(expected.blockerCode()),
                        "full Fabric API missing-target refusal code");
                check(expected.getMessage().contains("mixins target classes absent"),
                        "full Fabric API refusal lists absent targets");
                System.out.println("VictusFabricProviderIntegrationTest: full Fabric API incompatibilities refused explicitly");
                return;
            }
        }
        adapter.launch(request, lifecycle, report);

        check(FabricLoaderAdapter.FIXTURE_ENTRYPOINT_MARKER.equals(
                System.getProperty("victus.fixture.fabric.entrypoint")), "Fabric main entrypoint");
        check(FabricLoaderAdapter.FIXTURE_PROOF_MARKER.equals(
                System.getProperty("victus.fixture.fabric.proof")), "Knot/Mixin proof");
        check("VICTUS_FIXTURE_FABRIC_LIFECYCLE_API".equals(
                System.getProperty("victus.fixture.fabric.lifecycleApi")), "Fabric API lifecycle linkage");
        check(VictusFabricGameProvider.EXTERNAL_LIBRARY_MARKER.equals(
                System.getProperty("victus.fixture.fabric.externalLibrary")), "provider external library visibility");
        check(VictusFabricGameProvider.EXTERNAL_LIBRARY_MARKER.equals(
                System.getProperty("victus.fixture.target.externalLibrary")), "target external library visibility");
        check("true".equals(System.getProperty("victus.fixture.target.called")), "owned target delegated");
        check(lifecycle.state() == LifecycleState.TARGET_DELEGATED, "target lifecycle state");
        System.out.println("VictusFabricProviderIntegrationTest: discovery, entrypoint, Mixin, target ownership and external library visibility passed");
    }

    private static void clearProof() {
        System.clearProperty("victus.fixture.fabric.entrypoint");
        System.clearProperty("victus.fixture.fabric.proof");
        System.clearProperty("victus.fixture.fabric.lifecycleApi");
        System.clearProperty("victus.fixture.fabric.externalLibrary");
        System.clearProperty("victus.fixture.target.externalLibrary");
        System.clearProperty("victus.fixture.target.called");
    }

    private static List<Path> split(String classpath) {
        List<Path> paths = new ArrayList<>();
        for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isBlank()) paths.add(Path.of(entry).toAbsolutePath().normalize());
        }
        return List.copyOf(paths);
    }

    private static void createLifecycleApiFixture(Path jar) throws IOException {
        String metadata = """
                {"schemaVersion":1,"id":"fabric-lifecycle-events-v1","version":"4.1.3+4575b05f9e",
                 "name":"Fabric Lifecycle API owned-target fixture","environment":"server",
                 "depends":{"fabricloader":">=0.19.3"}}
                """;
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            copyClass(output, "net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.class");
        }
    }

    private static void createTargetJar(Path jar) throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            copyClass(output, "org/bukkit/craftbukkit/Main.class");
            copyClass(output, "net/minecraft/server/MinecraftServer.class");
            copyClass(output, "net/minecraft/resources/Identifier.class");
        }
    }

    private static void createExternalLibraryJar(Path jar) throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            copyClass(output, "cloud/victus/hybrid/fabric/fixture/ExternalRuntimeLibrary.class");
        }
    }

    private static void copyClass(JarOutputStream output, String resource) throws IOException {
        try (var input = VictusFabricProviderIntegrationTest.class.getResourceAsStream("/" + resource)) {
            if (input == null) throw new IOException("missing owned target class bytes: " + resource);
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

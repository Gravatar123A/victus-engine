// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.neoforge;

import cloud.victus.hybrid.common.ClasspathInspector;
import cloud.victus.hybrid.common.CompatibilityFingerprint;
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
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** NeoForge foundation adapter: validates the FML module plan and enters the real FML server main. */
public final class NeoForgeLoaderAdapter implements LoaderAdapter {
    public static final String NEOFORGE_VERSION = "26.2.0.57";
    public static final String FML_VERSION = "11.0.17";
    public static final String INSTALLER_FML_VERSION = "11.0.16";
    public static final String FML_MODULE = "fml_loader";
    public static final String FML_SERVER = "net.neoforged.fml.startup.Server";
    private static final String SUPPORTED_TARGET = "net.minecraft.server.Main";

    @Override
    public LoaderProfile profile() {
        return LoaderProfile.NEOFORGE;
    }

    @Override
    public String implementationVersion() {
        return "foundation-1";
    }

    @Override
    public PreflightResult preflight(LaunchRequest request) {
        List<String> diagnostics = new ArrayList<>();
        List<String> missing = ClasspathInspector.missing(request.loaderClasspath());
        if (!missing.isEmpty()) {
            diagnostics.add("NEOFORGE_RUNTIME_CLASSPATH_MISSING: " + String.join(",", missing));
        }
        if (!ClasspathInspector.containsClass(request.loaderClasspath(), FML_SERVER)) {
            diagnostics.add("NEOFORGE_FML_MISSING: loader classpath must contain " + FML_SERVER + " from FML " + FML_VERSION);
        }
        if (!SUPPORTED_TARGET.equals(request.targetMain())) {
            diagnostics.add("NEOFORGE_TARGET_UNSUPPORTED: FML server launches " + SUPPORTED_TARGET
                    + "; received " + request.targetMain() + ". Use the patched server artifact, not Paperclip.");
        }
        if (request.targetArtifact() == null || !Files.isRegularFile(request.targetArtifact())) {
            diagnostics.add("NEOFORGE_TARGET_ARTIFACT_MISSING: --hybrid-target-artifact must name the patched server jar");
        } else if (!ClasspathInspector.containsClass(List.of(request.targetArtifact()), SUPPORTED_TARGET)) {
            diagnostics.add("NEOFORGE_TARGET_MAIN_MISSING: patched server artifact lacks " + SUPPORTED_TARGET);
        }
        if (!Files.isDirectory(request.gameDirectory())) {
            diagnostics.add("NEOFORGE_GAME_DIRECTORY_MISSING: " + request.gameDirectory());
        }
        if (!hasModule(request.loaderClasspath(), FML_MODULE)) {
            diagnostics.add("NEOFORGE_FML_MODULE_MISSING: module path does not resolve " + FML_MODULE
                    + "; provide FML and every direct launch requirement from the installer-derived lock");
        }
        if (!hasModule(request.loaderClasspath(), "neoforge")) {
            diagnostics.add("NEOFORGE_PLATFORM_MODULE_MISSING: module path does not resolve the universal NeoForge module");
        }
        if (!ClasspathInspector.containsClass(request.loaderClasspath(),
                "net.neoforged.neoforgespi.transformation.ClassProcessor")) {
            diagnostics.add("NEOFORGE_CLASS_PROCESSOR_API_MISSING: FML transformation SPI is not assembled");
        }
        if (!ClasspathInspector.containsClass(request.loaderClasspath(),
                "net.neoforged.fml.loading.mixin.FMLMixinService")) {
            diagnostics.add("NEOFORGE_MIXIN_SERVICE_MISSING: FML Mixin service is not assembled");
        }
        CompatibilityFingerprint fingerprint = new CompatibilityFingerprint(
                "26.2", System.getProperty("victus.paperCommit", "75c0b485bf038c175d6f3e6efc67519cd5cd524d"),
                profile(), NEOFORGE_VERSION + "/fml-" + FML_VERSION, "neoforge-universal",
                System.getProperty("java.version"), List.of());
        if (diagnostics.isEmpty()) {
            diagnostics.add("NeoForge FML module plan ready: module=" + FML_MODULE + ", target=" + request.targetMain()
                    + ", gameDir=" + request.gameDirectory());
        }
        return new PreflightResult(diagnostics.size() == 1 && diagnostics.get(0).startsWith("NeoForge FML module plan ready"),
                diagnostics, fingerprint);
    }

    @Override
    public void launch(LaunchRequest request, LifecycleTracker lifecycle, StartupReport report)
            throws HybridLaunchException {
        // request.loaderClasspath is the installer-generated runtime graph. The merged game is supplied
        // as game content, never as an unpatched Paper jar pretending to be an FML module.
        List<Path> modulePath = new ArrayList<>(request.loaderClasspath());
        modulePath.addAll(request.targetClasspath());
        ModuleFinder finder = ModuleFinder.of(modulePath.toArray(Path[]::new));
        Set<String> roots = new LinkedHashSet<>();
        roots.add(FML_MODULE);
        roots.add("neoforge");
        Configuration configuration;
        try {
            configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), roots);
        } catch (java.lang.module.ResolutionException failure) {
            throw new HybridLaunchException("NEOFORGE_MODULE_RESOLUTION_FAILED",
                    "FML module graph did not resolve. Supply the locked direct launch requirements: " + failure.getMessage(), failure);
        }

        ModuleLayer.Controller controller = ModuleLayer.defineModulesWithOneLoader(
                configuration, List.of(ModuleLayer.boot()), ClassLoader.getPlatformClassLoader());
        ClassLoader fmlLoader = controller.layer().findLoader(FML_MODULE);
        lifecycle.transition(LifecycleState.TRANSFORMERS_READY);
        report.diagnostic(HybridMarkers.TRANSFORMER + "=fml-class-processor-plan");
        lifecycle.transition(LifecycleState.LOADER_ENTERED);
        report.diagnostic(HybridMarkers.LOADER_ENTERED + "=neoforge");

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(fmlLoader);
        try {
            Class<?> entrypoint = Class.forName(FML_SERVER, true, fmlLoader);
            Method main = entrypoint.getMethod("main", String[].class);
            main.invoke(null, (Object) fmlArguments(request));
            lifecycle.transition(LifecycleState.TARGET_DELEGATED);
            report.diagnostic(HybridMarkers.TARGET_DELEGATED + "=" + request.targetMain());
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new HybridLaunchException("NEOFORGE_FML_FAILED",
                    "FML entered but could not launch the patched Paper server: " + cause
                            + ". This is a concrete FML game-content/class-processor compatibility blocker.", cause);
        } catch (ReflectiveOperationException failure) {
            throw new HybridLaunchException("NEOFORGE_FML_ENTRY_FAILED", "Cannot enter FML server main: " + failure, failure);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static String[] fmlArguments(LaunchRequest request) {
        List<String> args = new ArrayList<>();
        args.add("--gameDir");
        args.add(request.gameDirectory().toString());
        args.add("--fml.neoForgeVersion");
        args.add(NEOFORGE_VERSION);
        args.add("--fml.mcVersion");
        args.add("26.2");
        args.add("--fml.neoFormVersion");
        args.add("2");
        args.addAll(request.targetArguments());
        return args.toArray(String[]::new);
    }

    static boolean hasModule(List<Path> paths, String moduleName) {
        try {
            return ModuleFinder.of(paths.toArray(Path[]::new)).find(moduleName).isPresent();
        } catch (RuntimeException invalidModule) {
            return false;
        }
    }
}

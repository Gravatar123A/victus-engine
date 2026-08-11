// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fabric;

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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fabric 0.19.3 adapter that enters Knot before the explicit patched server main is loaded. */
public final class FabricLoaderAdapter implements LoaderAdapter {
    public static final String LOADER_VERSION = "0.19.3";
    public static final String API_VERSION = "0.156.0+26.2";
    public static final String MINECRAFT_VERSION = "26.2";
    public static final String KNOT_SERVER = "net.fabricmc.loader.impl.launch.knot.KnotServer";
    private static final String KNOT = "net.fabricmc.loader.impl.launch.knot.Knot";
    private static final String ENV_TYPE = "net.fabricmc.api.EnvType";
    public static final String FIXTURE_ENTRYPOINT_MARKER = "VICTUS_FIXTURE_FABRIC_ENTRYPOINT";
    public static final String FIXTURE_MIXIN_MARKER = "VICTUS_FIXTURE_FABRIC_MIXIN";
    public static final String FIXTURE_PROOF_MARKER = "VICTUS_FIXTURE_FABRIC_PROOF";
    private static final String SUPPORTED_TARGET = "org.bukkit.craftbukkit.Main";
    private static final String VICTUS_PROVIDER = "cloud.victus.hybrid.fabric.VictusFabricGameProvider";

    @Override
    public LoaderProfile profile() {
        return LoaderProfile.FABRIC;
    }

    @Override
    public String implementationVersion() {
        return "victus-game-provider-1";
    }

    @Override
    public PreflightResult preflight(LaunchRequest request) {
        List<String> diagnostics = new ArrayList<>();
        List<String> missing = ClasspathInspector.missing(request.loaderClasspath());
        if (!missing.isEmpty()) {
            diagnostics.add("FABRIC_RUNTIME_CLASSPATH_MISSING: " + String.join(",", missing));
        }
        if (!ClasspathInspector.containsClass(request.loaderClasspath(), KNOT_SERVER)) {
            diagnostics.add("FABRIC_KNOT_MISSING: loader classpath must contain " + KNOT_SERVER + " from Fabric Loader " + LOADER_VERSION);
        }
        if (!ClasspathInspector.containsClass(request.loaderClasspath(), "org.spongepowered.asm.mixin.Mixin")) {
            diagnostics.add("FABRIC_MIXIN_MISSING: loader classpath must contain locked Sponge Mixin 0.17.3+mixin.0.8.7");
        }
        if (!ClasspathInspector.containsClass(request.loaderClasspath(), "org.objectweb.asm.ClassReader")) {
            diagnostics.add("FABRIC_ASM_MISSING: loader classpath must contain the locked ASM 9.10.1 runtime");
        }
        if (!ClasspathInspector.containsClass(request.adapterClasspath(), VICTUS_PROVIDER)) {
            diagnostics.add("FABRIC_VICTUS_PROVIDER_MISSING: adapter classpath must contain "
                    + VICTUS_PROVIDER + " and its GameProvider service metadata");
        }
        if (!SUPPORTED_TARGET.equals(request.targetMain())) {
            diagnostics.add("FABRIC_TARGET_UNSUPPORTED: VictusFabricGameProvider requires patched server entrypoint "
                    + SUPPORTED_TARGET + "; received " + request.targetMain()
                    + ". Use the class-bearing patched server jar, not Paperclip/bundler.");
        }
        if (request.targetArtifact() == null || !Files.isRegularFile(request.targetArtifact())) {
            diagnostics.add("FABRIC_TARGET_ARTIFACT_MISSING: --hybrid-target-artifact must name the patched server jar");
        } else if (!ClasspathInspector.containsClass(List.of(request.targetArtifact()), SUPPORTED_TARGET)) {
            diagnostics.add("FABRIC_TARGET_MAIN_MISSING: patched server artifact lacks " + SUPPORTED_TARGET);
        }
        List<String> missingTargetLibraries = ClasspathInspector.missing(request.targetClasspath());
        if (!missingTargetLibraries.isEmpty()) {
            diagnostics.add("FABRIC_TARGET_CLASSPATH_MISSING: " + String.join(",", missingTargetLibraries));
        }
        if (!Files.isDirectory(request.gameDirectory())) {
            diagnostics.add("FABRIC_GAME_DIRECTORY_MISSING: " + request.gameDirectory());
        }
        CompatibilityFingerprint fingerprint = new CompatibilityFingerprint(
                "26.2", System.getProperty("victus.paperCommit", "75c0b485bf038c175d6f3e6efc67519cd5cd524d"),
                profile(), LOADER_VERSION, API_VERSION, System.getProperty("java.version"), List.of());
        if (diagnostics.isEmpty()) {
            diagnostics.add("Fabric Knot preflight ready: explicit game jar=" + request.targetArtifact()
                    + ", target=" + request.targetMain() + ", mods=" + request.modsDirectory());
        }
        return new PreflightResult(diagnostics.size() == 1 && diagnostics.get(0).startsWith("Fabric Knot preflight ready"),
                diagnostics, fingerprint);
    }

    @Override
    public void launch(LaunchRequest request, LifecycleTracker lifecycle, StartupReport report)
            throws HybridLaunchException {
        List<URL> runtime = new ArrayList<>();
        for (Path path : request.loaderClasspath()) {
            add(runtime, path);
        }
        // GameProvider is a Loader-owned SPI, so the adapter/provider jar must be visible to Knot's
        // platform loader as well as to the outer ServiceLoader used by VictusHybridLauncher.
        for (Path path : request.adapterClasspath()) {
            add(runtime, path);
        }
        // Do not put target classes or libraries on the platform loader. The provider exposes them only
        // from unlockClassPath(), after Fabric discovery and Mixin transformer initialization.

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("victus.fabric.provider", "true");
        properties.put("victus.fabric.targetJar", request.targetArtifact().toString());
        properties.put("victus.fabric.targetMain", request.targetMain());
        properties.put("victus.fabric.targetLibraries", join(request.targetClasspath()));
        properties.put("victus.fabric.gameDir", request.gameDirectory().toString());
        properties.put("fabric.skipMcProvider", "true");
        properties.put("fabric.gameVersion", MINECRAFT_VERSION);
        properties.put("fabric.gameMappingNamespace", "official");
        properties.put("fabric.runtimeMappingNamespace", "official");
        properties.put("fabric.defaultModDistributionNamespace", "official");
        properties.put("fabric.modsFolder", request.modsDirectory().toString());
        properties.put("fabric.side", "server");
        properties.put("fabric.noGui", "true");
        properties.put("fabric.log.file", request.gameDirectory().resolve("logs/victus-fabric-loader.log").toString());
        properties.put("fabric.debug.throwDirectly", "true");
        properties.put("fabric.debug.logTransformErrors", "true");
        Map<String, String> previous = install(properties);
        lifecycle.transition(LifecycleState.TRANSFORMERS_READY);
        report.diagnostic(HybridMarkers.TRANSFORMER + "=fabric-knot-mixin");

        try (URLClassLoader knot = new URLClassLoader("victus-fabric-knot", runtime.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Thread thread = Thread.currentThread();
            ClassLoader previousContext = thread.getContextClassLoader();
            Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
            thread.setContextClassLoader(knot);
            lifecycle.transition(LifecycleState.LOADER_ENTERED);
            report.diagnostic(HybridMarkers.LOADER_ENTERED + "=fabric");
            try {
                Class<?> knotClass = Class.forName(KNOT, true, knot);
                Class<?> envTypeClass = Class.forName(ENV_TYPE, true, knot);
                if (knotClass.getClassLoader() != knot || envTypeClass.getClassLoader() != knot) {
                    throw new HybridLaunchException("FABRIC_KNOT_CLASSLOADER_INVALID",
                            "Knot was not loaded by the isolated Fabric runtime classloader: " + knotClass.getClassLoader());
                }
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object server = Enum.valueOf((Class<? extends Enum>) envTypeClass.asSubclass(Enum.class), "SERVER");
                Method launch = knotClass.getMethod("launch", String[].class, envTypeClass);
                // Call Knot.launch directly. KnotServer.main delegates here, but its FormattedException path can
                // invoke FabricGuiEntry and terminate/suppress the nested entrypoint cause before the host reports it.
                launch.invoke(null, request.targetArguments().toArray(String[]::new), server);
                lifecycle.transition(LifecycleState.TARGET_DELEGATED);
                report.diagnostic(HybridMarkers.TARGET_DELEGATED + "=" + request.targetMain());
            } finally {
                // Knot installs a process-global handler that may call FabricGuiEntry from a classloader which
                // cannot see Loader internals. Restore the host handler while this isolated runtime is still live.
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
                thread.setContextClassLoader(previousContext);
            }
        } catch (InvocationTargetException failure) {
            Throwable cause = unwrapInvocation(failure);
            report.diagnostic("FABRIC_FAILURE_CAUSE_CHAIN=" + causeChain(cause));
            report.diagnostic("FABRIC_FAILURE_STACK=" + stackTrace(cause));
            throw new HybridLaunchException(classify(cause),
                    "Knot entered but could not launch the patched Victus server: " + causeChain(cause), cause);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new HybridLaunchException("FABRIC_KNOT_ENTRY_FAILED",
                    "Cannot enter Fabric Knot " + KNOT_SERVER + ": " + failure, failure);
        } finally {
            restore(previous);
        }
    }

    private static String classify(Throwable failure) {
        String text = causeChain(failure);
        if (text.contains("MixinApplyError") || text.contains("InvalidInjectionException")
                || text.contains("InjectionError")) {
            return "FABRIC_API_MIXIN_INCOMPATIBLE";
        }
        for (String code : List.of("FABRIC_FIXTURE_NOT_DISCOVERED", "FABRIC_FIXTURE_ENTRYPOINT_MISSING",
                "FABRIC_FIXTURE_API_MISSING", "FABRIC_FIXTURE_CLASSLOADER_MISMATCH",
                "FABRIC_FIXTURE_MIXIN_MISSING", "FABRIC_TARGET_LIBRARY_NOT_VISIBLE",
                "FABRIC_TARGET_LIBRARY_CLASSLOADER_MISMATCH", "FABRIC_TARGET_CLASSPATH_UNLOCK_FAILED",
                "FABRIC_API_MODULE_INCOMPATIBLE",
                "FABRIC_LIFECYCLE_BRIDGE_FAILED", "FABRIC_REGISTRY_BRIDGE_FAILED")) {
            if (text.contains(code)) return code;
        }
        return "FABRIC_KNOT_FAILED";
    }

    private static Throwable unwrapInvocation(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    private static String causeChain(Throwable failure) {
        StringBuilder out = new StringBuilder();
        Throwable current = failure;
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (!out.isEmpty()) out.append(" -> ");
            out.append(current.getClass().getName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                out.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return out.toString();
    }

    private static String stackTrace(Throwable failure) {
        StringWriter buffer = new StringWriter();
        failure.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    private static String join(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static Map<String, String> install(Map<String, String> values) {
        Map<String, String> previous = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        return previous;
    }

    private static void restore(Map<String, String> values) {
        values.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    private static void add(List<URL> urls, Path path) throws HybridLaunchException {
        try {
            urls.add(path.toUri().toURL());
        } catch (java.net.MalformedURLException failure) {
            throw new HybridLaunchException("FABRIC_CLASSPATH_INVALID", "Invalid Fabric classpath entry " + path, failure);
        }
    }
}

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
    public static final String KNOT_SERVER = "net.fabricmc.loader.impl.launch.knot.KnotServer";
    private static final String SUPPORTED_TARGET = "net.minecraft.server.Main";

    @Override
    public LoaderProfile profile() {
        return LoaderProfile.FABRIC;
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
            diagnostics.add("FABRIC_RUNTIME_CLASSPATH_MISSING: " + String.join(",", missing));
        }
        if (!ClasspathInspector.containsClass(request.loaderClasspath(), KNOT_SERVER)) {
            diagnostics.add("FABRIC_KNOT_MISSING: loader classpath must contain " + KNOT_SERVER + " from Fabric Loader " + LOADER_VERSION);
        }
        if (!SUPPORTED_TARGET.equals(request.targetMain())) {
            diagnostics.add("FABRIC_TARGET_UNSUPPORTED: Fabric's MinecraftGameProvider selects " + SUPPORTED_TARGET
                    + "; received " + request.targetMain() + ". Use the patched server artifact, not Paperclip.");
        }
        if (request.targetArtifact() == null || !Files.isRegularFile(request.targetArtifact())) {
            diagnostics.add("FABRIC_TARGET_ARTIFACT_MISSING: --hybrid-target-artifact must name the patched server jar");
        } else if (!ClasspathInspector.containsClass(List.of(request.targetArtifact()), SUPPORTED_TARGET)) {
            diagnostics.add("FABRIC_TARGET_MAIN_MISSING: patched server artifact lacks " + SUPPORTED_TARGET);
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
        add(runtime, request.targetArtifact());
        for (Path path : request.targetClasspath()) {
            add(runtime, path);
        }

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fabric.gameJarPath.server", request.targetArtifact().toString());
        properties.put("fabric.gameVersion", "26.2");
        properties.put("fabric.gameMappingNamespace", "official");
        properties.put("fabric.runtimeMappingNamespace", "official");
        properties.put("fabric.defaultModDistributionNamespace", "official");
        properties.put("fabric.modsFolder", request.modsDirectory().toString());
        properties.put("fabric.side", "server");
        properties.put("fabric.noGui", "true");
        Map<String, String> previous = install(properties);
        lifecycle.transition(LifecycleState.TRANSFORMERS_READY);
        report.diagnostic(HybridMarkers.TRANSFORMER + "=fabric-knot-mixin");

        try (URLClassLoader knot = new URLClassLoader("victus-fabric-knot", runtime.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Thread thread = Thread.currentThread();
            ClassLoader previousContext = thread.getContextClassLoader();
            thread.setContextClassLoader(knot);
            lifecycle.transition(LifecycleState.LOADER_ENTERED);
            report.diagnostic(HybridMarkers.LOADER_ENTERED + "=fabric");
            try {
                Class<?> mainClass = Class.forName(KNOT_SERVER, true, knot);
                Method main = mainClass.getMethod("main", String[].class);
                main.invoke(null, (Object) request.targetArguments().toArray(String[]::new));
                lifecycle.transition(LifecycleState.TARGET_DELEGATED);
                report.diagnostic(HybridMarkers.TARGET_DELEGATED + "=" + request.targetMain());
            } finally {
                thread.setContextClassLoader(previousContext);
            }
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new HybridLaunchException("FABRIC_KNOT_FAILED",
                    "Knot entered but could not launch the patched Paper server: " + cause
                            + ". This is a concrete Paper game-provider/transform compatibility blocker.", cause);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new HybridLaunchException("FABRIC_KNOT_ENTRY_FAILED",
                    "Cannot enter Fabric Knot " + KNOT_SERVER + ": " + failure, failure);
        } finally {
            restore(previous);
        }
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

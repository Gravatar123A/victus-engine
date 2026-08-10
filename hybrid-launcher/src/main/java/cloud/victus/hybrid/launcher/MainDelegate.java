// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.launcher;

import cloud.victus.hybrid.common.HybridLaunchException;
import cloud.victus.hybrid.common.LaunchRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Delegates exactly once without compile-time references to the target application. */
public final class MainDelegate {
    private MainDelegate() {
    }

    public static void invoke(LaunchRequest request) throws HybridLaunchException {
        List<URL> urls = new ArrayList<>();
        if (request.targetArtifact() != null) {
            add(urls, request.targetArtifact());
        }
        for (Path path : request.targetClasspath()) {
            add(urls, path);
        }

        ClassLoader parent = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader targetLoader = new URLClassLoader("victus-target", urls.toArray(URL[]::new), parent)) {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(targetLoader);
            try {
                Class<?> target = Class.forName(request.targetMain(), true, targetLoader);
                Method main = target.getMethod("main", String[].class);
                main.invoke(null, (Object) request.targetArguments().toArray(String[]::new));
            } finally {
                thread.setContextClassLoader(previous);
            }
        } catch (InvocationTargetException targetFailure) {
            Throwable cause = targetFailure.getCause() == null ? targetFailure : targetFailure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new HybridLaunchException("TARGET_MAIN_FAILED", "Target main failed: " + cause, cause);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new HybridLaunchException("TARGET_DELEGATION_FAILED",
                    "Cannot invoke target main " + request.targetMain() + ": " + failure, failure);
        }
    }

    private static void add(List<URL> urls, Path path) throws HybridLaunchException {
        if (!Files.exists(path)) {
            throw new HybridLaunchException("TARGET_CLASSPATH_MISSING", "Target classpath entry does not exist: " + path);
        }
        try {
            urls.add(path.toUri().toURL());
        } catch (java.net.MalformedURLException failure) {
            throw new HybridLaunchException("TARGET_CLASSPATH_INVALID", "Invalid target classpath entry: " + path, failure);
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.launcher;

import cloud.victus.hybrid.common.HybridLaunchException;
import cloud.victus.hybrid.common.LoaderAdapter;
import cloud.victus.hybrid.common.LoaderProfile;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Child loader used only after profile selection. Common contracts remain parent-owned. */
public final class IsolatedAdapterLoader implements AutoCloseable {
    private final URLClassLoader classLoader;

    private IsolatedAdapterLoader(URLClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public static IsolatedAdapterLoader open(List<Path> classpath) throws HybridLaunchException {
        List<URL> urls = new ArrayList<>();
        for (Path path : classpath) {
            if (!Files.exists(path)) {
                throw new HybridLaunchException("ADAPTER_CLASSPATH_MISSING", "Adapter classpath entry does not exist: " + path);
            }
            try {
                urls.add(path.toUri().toURL());
            } catch (java.net.MalformedURLException failure) {
                throw new HybridLaunchException("ADAPTER_CLASSPATH_INVALID", "Invalid adapter classpath: " + path, failure);
            }
        }
        return new IsolatedAdapterLoader(new URLClassLoader(
                "victus-hybrid-adapter", urls.toArray(URL[]::new), LoaderAdapter.class.getClassLoader()));
    }

    public LoaderAdapter adapter(LoaderProfile profile) throws HybridLaunchException {
        List<LoaderAdapter> matches = ServiceLoader.load(LoaderAdapter.class, classLoader).stream()
                .map(ServiceLoader.Provider::get)
                .filter(adapter -> adapter.profile() == profile)
                .toList();
        if (matches.size() != 1) {
            throw new HybridLaunchException("ADAPTER_DISCOVERY_FAILED",
                    "Expected exactly one " + profile.name().toLowerCase() + " LoaderAdapter but found " + matches.size());
        }
        return matches.get(0);
    }

    @Override
    public void close() throws IOException {
        classLoader.close();
    }
}

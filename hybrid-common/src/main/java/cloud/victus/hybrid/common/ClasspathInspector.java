// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Small, non-loading artifact inspection helpers used by profile preflight. */
public final class ClasspathInspector {
    private ClasspathInspector() {
    }

    public static List<String> missing(List<Path> paths) {
        List<String> missing = new ArrayList<>();
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                missing.add(path.toAbsolutePath().normalize().toString());
            }
        }
        return List.copyOf(missing);
    }

    public static boolean containsClass(List<Path> paths, String binaryClassName) {
        String entry = binaryClassName.replace('.', '/') + ".class";
        for (Path path : paths) {
            try (JarFile jar = new JarFile(path.toFile())) {
                if (jar.getJarEntry(entry) != null) {
                    return true;
                }
            } catch (IOException ignored) {
                // Preflight reports the absent class; unreadable jars are not accepted as matches.
            }
        }
        return false;
    }

    public static String implementationVersion(List<Path> paths, String className) {
        String classEntry = className.replace('.', '/') + ".class";
        for (Path path : paths) {
            try (JarFile jar = new JarFile(path.toFile())) {
                if (jar.getJarEntry(classEntry) == null || jar.getManifest() == null) {
                    continue;
                }
                Attributes attributes = jar.getManifest().getMainAttributes();
                String version = attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                if (version == null) {
                    version = attributes.getValue("Build-Number");
                }
                return version == null ? "unknown" : version;
            } catch (IOException ignored) {
                // Continue to the next entry.
            }
        }
        return "missing";
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.launcher;

import cloud.victus.hybrid.common.LaunchRequest;
import cloud.victus.hybrid.common.LoaderProfile;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Strict pre-main parser. Hybrid options are removed; arguments after {@code --} are delegated unchanged. */
public final class LauncherArguments {
    private static final java.util.Set<String> KNOWN_OPTIONS = java.util.Set.of(
            "hybrid-config", "hybrid-profile", "hybrid-target-main", "hybrid-target-artifact",
            "hybrid-target-classpath", "hybrid-adapter-classpath", "hybrid-loader-classpath",
            "hybrid-game-dir", "hybrid-mods-dir", "hybrid-report");

    private LauncherArguments() {
    }

    public static LaunchRequest parse(String[] arguments) throws IOException {
        Map<String, String> cli = new LinkedHashMap<>();
        List<String> targetArguments = new ArrayList<>();
        boolean target = false;
        for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i];
            if (target) {
                targetArguments.add(arg);
                continue;
            }
            if ("--".equals(arg)) {
                target = true;
                continue;
            }
            if (!arg.startsWith("--hybrid-")) {
                throw new IllegalArgumentException(
                        "Target arguments must follow the '--' separator; unexpected launcher argument: " + arg);
            }
            int equals = arg.indexOf('=');
            String key = equals >= 0 ? arg.substring(2, equals) : arg.substring(2);
            if (!KNOWN_OPTIONS.contains(key)) {
                throw new IllegalArgumentException("Unknown launcher option --" + key);
            }
            String value;
            if (equals >= 0) {
                value = arg.substring(equals + 1);
            } else if (i + 1 < arguments.length) {
                value = arguments[++i];
            } else {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            if (cli.put(key, value) != null) {
                throw new IllegalArgumentException("Duplicate option --" + key);
            }
        }

        Properties config = loadConfig(cli.get("hybrid-config"));
        LoaderProfile profile = LoaderProfile.parse(resolve(cli, config, "hybrid-profile", "profile", "disabled"));
        String targetMain = required(resolve(cli, config, "hybrid-target-main", "targetMain", null), "--hybrid-target-main");
        String artifactValue = resolve(cli, config, "hybrid-target-artifact", "targetArtifact", null);
        Path targetArtifact = artifactValue == null || artifactValue.isBlank() ? null : Path.of(artifactValue).toAbsolutePath().normalize();
        List<Path> targetClasspath = paths(resolve(cli, config, "hybrid-target-classpath", "targetClasspath", ""));
        List<Path> adapterClasspath = paths(resolve(cli, config, "hybrid-adapter-classpath", "adapterClasspath", ""));
        List<Path> loaderClasspath = paths(resolve(cli, config, "hybrid-loader-classpath", "loaderClasspath", ""));
        Path gameDir = Path.of(resolve(cli, config, "hybrid-game-dir", "gameDirectory", "."));
        Path modsDir = Path.of(resolve(cli, config, "hybrid-mods-dir", "modsDirectory", "mods"));
        Path report = Path.of(resolve(cli, config, "hybrid-report", "startupReport", "logs/victus-hybrid-startup.txt"));

        return new LaunchRequest(profile, targetMain, targetArtifact, targetClasspath, adapterClasspath,
                loaderClasspath, gameDir, modsDir, targetArguments, report);
    }

    private static Properties loadConfig(String value) throws IOException {
        Properties config = new Properties();
        if (value != null && !value.isBlank()) {
            try (Reader reader = Files.newBufferedReader(Path.of(value))) {
                config.load(reader);
            }
        }
        return config;
    }

    private static String resolve(Map<String, String> cli, Properties config, String option, String property, String fallback) {
        String value = cli.get(option);
        if (value != null) {
            return value;
        }
        value = config.getProperty(property);
        if (value != null) {
            return value.trim();
        }
        String system = System.getProperty("victus.hybrid." + property);
        return system == null ? fallback : system.trim();
    }

    private static String required(String value, String option) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(option + " is required");
        }
        return value;
    }

    private static List<Path> paths(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String entry : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isBlank()) {
                paths.add(Path.of(entry).toAbsolutePath().normalize());
            }
        }
        return List.copyOf(paths);
    }
}

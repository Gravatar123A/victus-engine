// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.plugin;

import cloud.victus.core.doctor.ConfigPatch;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/** Applies a lag-doctor {@link ConfigPatch} to victus.yml or server.properties on disk (the I/O the
 *  pure core deliberately leaves to the fork). */
final class ConfigApplier {

    private final File serverDir;

    ConfigApplier(File serverDir) {
        this.serverDir = serverDir;
    }

    /** Applies the patch and returns a human-readable description. */
    String apply(ConfigPatch patch) throws IOException {
        if (ConfigPatch.SERVER_PROPERTIES.equals(patch.file())) {
            File f = new File(serverDir, ConfigPatch.SERVER_PROPERTIES);
            Properties props = new Properties();
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    props.load(in);
                }
            }
            props.setProperty(patch.key(), String.valueOf(patch.value()));
            try (FileOutputStream out = new FileOutputStream(f)) {
                props.store(out, "edited by /victus doctor");
            }
            return patch.file() + " " + patch.key() + " = " + patch.value() + "  (needs a restart to take effect)";
        }

        File f = new File(serverDir, ConfigPatch.VICTUS_YML);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        yaml.set(patch.key(), patch.value()); // dotted path -> nested
        yaml.save(f);
        return patch.file() + " " + patch.key() + " = " + patch.value();
    }
}

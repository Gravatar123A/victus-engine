// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.plugin;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a Bukkit {@link ConfigurationSection} (parsed from victus.yml) into the plain nested
 * {@code Map<String,Object>} that {@link cloud.victus.core.config.ConfigResolver} expects — keeping
 * victus-core free of any Bukkit/YAML dependency.
 */
final class YamlMaps {
    private YamlMaps() {}

    static Map<String, Object> toNestedMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (section == null) return out;
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                out.put(key, toNestedMap(child));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }
}
